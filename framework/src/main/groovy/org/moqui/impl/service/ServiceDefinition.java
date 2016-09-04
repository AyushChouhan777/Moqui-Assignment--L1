/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.service;

import org.apache.commons.validator.routines.CreditCardValidator;
import org.apache.commons.validator.routines.EmailValidator;
import org.apache.commons.validator.routines.UrlValidator;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;

import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.impl.StupidJavaUtilities;
import org.moqui.impl.actions.XmlAction;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.impl.entity.EntityDefinition;
import org.moqui.impl.service.ServiceJavaUtil.ParameterInfo;
import org.moqui.impl.util.FtlNodeWrapper;
import org.moqui.util.MNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

public class ServiceDefinition {
    protected static final Logger logger = LoggerFactory.getLogger(ServiceDefinition.class);
    private static final EmailValidator emailValidator = EmailValidator.getInstance();
    private static final UrlValidator urlValidator = new UrlValidator(UrlValidator.ALLOW_ALL_SCHEMES);

    public final ServiceFacadeImpl sfi;
    public final MNode serviceNode;
    public final MNode inParametersNode;
    public final MNode outParametersNode;

    private final LinkedHashMap<String, ParameterInfo> inParameterInfoMap = new LinkedHashMap<>();
    private final LinkedHashMap<String, ParameterInfo> outParameterInfoMap = new LinkedHashMap<>();
    private final ArrayList<String> inParameterNameList = new ArrayList<>();
    private final ArrayList<String> outParameterNameList = new ArrayList<>();

    public final String path;
    public final String verb;
    public final String noun;
    public final String serviceName;
    public final String serviceNameNoHash;

    public final String location;
    public final String method;
    public final XmlAction xmlAction;

    public final String authenticate;
    public final String serviceType;
    public final boolean txIgnore;
    public final boolean txForceNew;
    public final boolean txUseCache;
    public final boolean noTxCache;
    public final Integer txTimeout;
    public final boolean validate;

    public final boolean hasSemaphore;
    public final String semaphore, semaphoreParameter;
    public final long semaphoreIgnoreMillis, semaphoreSleepTime, semaphoreTimeoutTime;

    public ServiceDefinition(ServiceFacadeImpl sfi, String path, MNode sn) {
        this.sfi = sfi;
        this.serviceNode = sn.deepCopy(null);
        this.path = path;
        this.verb = serviceNode.attribute("verb");
        this.noun = serviceNode.attribute("noun");

        serviceName = makeServiceName(path, verb, noun);
        serviceNameNoHash = makeServiceNameNoHash(path, verb, noun);
        location = serviceNode.attribute("location");
        method = serviceNode.attribute("method");

        MNode inParameters = new MNode("in-parameters", null);
        MNode outParameters = new MNode("out-parameters", null);

        // handle implements elements
        if (serviceNode.hasChild("implements")) for (MNode implementsNode : serviceNode.children("implements")) {
            final String implServiceName = implementsNode.attribute("service");
            String implRequired = implementsNode.attribute("required");// no default here, only used if has a value
            if (implRequired != null && implRequired.isEmpty()) implRequired = null;
            ServiceDefinition sd = sfi.getServiceDefinition(implServiceName);
            if (sd == null) throw new IllegalArgumentException("Service " + implServiceName +
                    " not found, specified in service.implements in service " + serviceName);

            // these are the first params to be set, so just deep copy them over
            if (sd.serviceNode.first("in-parameters").hasChild("parameter")) {
                for (MNode parameter : sd.serviceNode.first("in-parameters").children("parameter")) {
                    MNode newParameter = parameter.deepCopy(null);
                    if (implRequired != null) newParameter.getAttributes().put("required", implRequired);
                    inParameters.append(newParameter);
                }
            }

            if (sd.serviceNode.first("out-parameters").hasChild("parameter")) {
                for (MNode parameter : sd.serviceNode.first("out-parameters").children("parameter")) {
                    MNode newParameter = parameter.deepCopy(null);
                    if (implRequired != null) newParameter.getAttributes().put("required", implRequired);
                    outParameters.append(newParameter);
                }
            }
        }

        // expand auto-parameters and merge parameter in in-parameters and out-parameters
        // if noun is a valid entity name set it on parameters with valid field names on it
        EntityDefinition ed = null;
        if (sfi.getEcfi().getEntityFacade().isEntityDefined(this.noun))
            ed = sfi.getEcfi().getEntityFacade().getEntityDefinition(this.noun);
        if (serviceNode.hasChild("in-parameters")) {
            for (MNode paramNode : serviceNode.first("in-parameters").getChildren()) {
                if ("auto-parameters".equals(paramNode.getName())) {
                    mergeAutoParameters(inParameters, paramNode);
                } else if (paramNode.getName().equals("parameter")) {
                    mergeParameter(inParameters, paramNode, ed);
                }
            }
        }

        if (serviceNode.hasChild("out-parameters")) {
            for (MNode paramNode : serviceNode.first("out-parameters").getChildren()) {
                if ("auto-parameters".equals(paramNode.getName())) {
                    mergeAutoParameters(outParameters, paramNode);
                } else if ("parameter".equals(paramNode.getName())) {
                    mergeParameter(outParameters, paramNode, ed);
                }
            }
        }

        // replace the in-parameters and out-parameters Nodes for the service
        if (serviceNode.hasChild("in-parameters")) serviceNode.remove("in-parameters");
        serviceNode.append(inParameters);
        if (serviceNode.hasChild("out-parameters")) serviceNode.remove("out-parameters");
        serviceNode.append(outParameters);

        if (logger.isTraceEnabled()) logger.trace("After merge for service " + serviceName + " node is:\n" + FtlNodeWrapper.prettyPrintNode(serviceNode));

        // if this is an inline service, get that now
        if (serviceNode.hasChild("actions")) {
            xmlAction = new XmlAction(sfi.getEcfi(), serviceNode.first("actions"), serviceName);
        } else {
            xmlAction = null;
        }

        final String authenticateAttr = serviceNode.attribute("authenticate");
        authenticate = authenticateAttr != null && !authenticateAttr.isEmpty() ? authenticateAttr : "true";
        final String typeAttr = serviceNode.attribute("type");
        serviceType = typeAttr != null && !typeAttr.isEmpty() ? typeAttr : "inline";
        String transactionAttr = serviceNode.attribute("transaction");
        txIgnore = "ignore".equals(transactionAttr);
        txForceNew = "force-new".equals(transactionAttr) || "force-cache".equals(transactionAttr);
        txUseCache = "cache".equals(transactionAttr) || "force-cache".equals(transactionAttr);
        noTxCache = "true".equals(serviceNode.attribute("no-tx-cache"));
        String txTimeoutAttr = serviceNode.attribute("transaction-timeout");
        if (txTimeoutAttr != null && !txTimeoutAttr.isEmpty()) {
            txTimeout = Integer.valueOf(txTimeoutAttr);
        } else {
            txTimeout = null;
        }

        semaphore = serviceNode.attribute("semaphore");
        hasSemaphore = semaphore != null && semaphore.length() > 0 && !"none".equals(semaphore);
        semaphoreParameter = serviceNode.attribute("semaphore-parameter");
        String ignoreAttr = serviceNode.attribute("semaphore-ignore");
        if (ignoreAttr == null || ignoreAttr.isEmpty()) ignoreAttr = "3600";
        semaphoreIgnoreMillis = Long.parseLong(ignoreAttr) * 1000;
        String sleepAttr = serviceNode.attribute("semaphore-sleep");
        if (sleepAttr == null || sleepAttr.isEmpty()) sleepAttr = "5";
        semaphoreSleepTime = Long.parseLong(sleepAttr) * 1000;
        String timeoutAttr = serviceNode.attribute("semaphore-timeout");
        if (timeoutAttr == null || timeoutAttr.isEmpty()) timeoutAttr = "120";
        semaphoreTimeoutTime = Long.parseLong(timeoutAttr) * 1000;

        // validate defaults to true
        validate = !"false".equals(serviceNode.attribute("validate"));

        inParametersNode = serviceNode.first("in-parameters");
        outParametersNode = serviceNode.first("out-parameters");

        if (inParametersNode != null) for (MNode parameter : inParametersNode.children("parameter")) {
            String parameterName = parameter.attribute("name");
            inParameterInfoMap.put(parameterName, new ParameterInfo(this, parameter));
            inParameterNameList.add(parameterName);
        }
        if (outParametersNode != null) for (MNode parameter : outParametersNode.children("parameter")) {
            String parameterName = parameter.attribute("name");
            outParameterInfoMap.put(parameterName, new ParameterInfo(this, parameter));
            outParameterNameList.add(parameterName);
        }
    }

    private void mergeAutoParameters(MNode parametersNode, MNode autoParameters) {
        String entityName = autoParameters.attribute("entity-name");
        if (entityName == null || entityName.isEmpty()) entityName = noun;
        if (entityName == null || entityName.isEmpty()) throw new IllegalArgumentException("Error in auto-parameters in service " +
                serviceName + ", no auto-parameters.@entity-name and no service.@noun for a default");
        EntityDefinition ed = sfi.ecfi.getEntityFacade().getEntityDefinition(entityName);
        if (ed == null) throw new IllegalArgumentException("Error in auto-parameters in service " + serviceName + ", the entity-name or noun [" + entityName + "] is not a valid entity name");

        Set<String> fieldsToExclude = new HashSet<>();
        for (MNode excludeNode : autoParameters.children("exclude")) {
            fieldsToExclude.add(excludeNode.attribute("field-name"));
        }


        String includeStr = autoParameters.attribute("include");
        if (includeStr == null || includeStr.isEmpty()) includeStr = "all";
        String requiredStr = autoParameters.attribute("required");
        if (requiredStr == null || requiredStr.isEmpty()) requiredStr = "false";
        String allowHtmlStr = autoParameters.attribute("allow-html");
        if (allowHtmlStr == null || allowHtmlStr.isEmpty()) allowHtmlStr = "none";
        for (String fieldName : ed.getFieldNames("all".equals(includeStr) || "pk".equals(includeStr), "all".equals(includeStr) || "nonpk".equals(includeStr))) {
            if (fieldsToExclude.contains(fieldName)) continue;

            String javaType = sfi.getEcfi().getEntityFacade().getFieldJavaType(ed.getFieldInfo(fieldName).type, ed);
            Map<String, String> map = new LinkedHashMap<>(5);
            map.put("type", javaType);
            map.put("required", requiredStr);
            map.put("allow-html", allowHtmlStr);
            map.put("entity-name", ed.fullEntityName);
            map.put("field-name", fieldName);
            mergeParameter(parametersNode, fieldName, map);
        }
    }

    private void mergeParameter(MNode parametersNode, MNode overrideParameterNode, EntityDefinition ed) {
        MNode baseParameterNode = mergeParameter(parametersNode, overrideParameterNode.attribute("name"), overrideParameterNode.getAttributes());
        // merge description, ParameterValidations
        for (MNode childNode : overrideParameterNode.getChildren()) {
            if ("description".equals(childNode.getName())) {
                if (baseParameterNode.hasChild(childNode.getName())) baseParameterNode.remove(childNode.getName());
            }

            if ("auto-parameters".equals(childNode.getName())) {
                mergeAutoParameters(baseParameterNode, childNode);
            } else if ("parameter".equals(childNode.getName())) {
                mergeParameter(baseParameterNode, childNode, ed);
            } else {
                // is a validation, just add it in, or the original has been removed so add the new one
                baseParameterNode.append(childNode);
            }

        }

        String entityNameAttr = baseParameterNode.attribute("entity-name");
        if (entityNameAttr != null && !entityNameAttr.isEmpty()) {
            String fieldNameAttr = baseParameterNode.attribute("field-name");
            if (fieldNameAttr == null || fieldNameAttr.isEmpty())
                baseParameterNode.getAttributes().put("field-name", baseParameterNode.attribute("name"));
        } else if (ed != null && ed.isField(baseParameterNode.attribute("name"))) {
            baseParameterNode.getAttributes().put("entity-name", ed.fullEntityName);
            baseParameterNode.getAttributes().put("field-name", baseParameterNode.attribute("name"));
        }

    }

    private static MNode mergeParameter(MNode parametersNode, final String parameterName, Map<String, String> attributeMap) {
        MNode baseParameterNode = parametersNode.first("parameter", "name", parameterName);
        if (baseParameterNode == null) {
            Map<String, String> map = new HashMap<>(1); map.put("name", parameterName);
            baseParameterNode = parametersNode.append("parameter", map);
        }
        baseParameterNode.getAttributes().putAll(attributeMap);
        return baseParameterNode;
    }

    public static String makeServiceName(String path, String verb, String noun) {
        return (path != null && !path.isEmpty() ? path + "." : "") + verb + (noun != null && !noun.isEmpty() ? "#" + noun : "");
    }

    public static String makeServiceNameNoHash(String path, String verb, String noun) {
        return (path != null && !path.isEmpty() ? path + "." : "") + verb + (noun != null ? noun : "");
    }

    public static String getPathFromName(String serviceName) {
        String p = serviceName;
        // do hash first since a noun following hash may have dots in it
        if (p.contains("#")) p = p.substring(0, p.indexOf("#"));
        if (!p.contains(".")) return null;
        return p.substring(0, p.lastIndexOf("."));
    }

    public static String getVerbFromName(String serviceName) {
        String v = serviceName;
        // do hash first since a noun following hash may have dots in it
        if (v.contains("#")) v = v.substring(0, v.indexOf("#"));
        if (v.contains(".")) v = v.substring(v.lastIndexOf(".") + 1);
        return v;
    }

    public static String getNounFromName(String serviceName) {
        if (!serviceName.contains("#")) return null;
        return serviceName.substring(serviceName.lastIndexOf("#") + 1);
    }

    public static ArtifactExecutionInfo.AuthzAction getVerbAuthzActionEnum(String theVerb) {
        // default to require the "All" authz action, and for special verbs default to something more appropriate
        ArtifactExecutionInfo.AuthzAction authzAction = verbAuthzActionEnumMap.get(theVerb);
        if (authzAction == null) authzAction = ArtifactExecutionInfo.AUTHZA_ALL;
        return authzAction;
    }

    public MNode getInParameter(String name) {
        ParameterInfo pi = inParameterInfoMap.get(name);
        if (pi == null) return null;
        return pi.parameterNode;
    }

    public ArrayList<String> getInParameterNames() {
        return inParameterNameList;
    }

    public MNode getOutParameter(String name) {
        ParameterInfo pi = outParameterInfoMap.get(name);
        if (pi == null) return null;
        return pi.parameterNode;
    }

    public ArrayList<String> getOutParameterNames() {
        return outParameterNameList;
    }

    public void convertValidateCleanParameters(Map<String, Object> parameters, ExecutionContextImpl eci) {
        // logger.warn("BEFORE ${serviceName} convertValidateCleanParameters: ${parameters.toString()}")

        // even if validate is false still apply defaults, convert defined params, etc
        checkParameterMap("", parameters, parameters, inParameterInfoMap, eci);

        // logger.warn("AFTER ${serviceName} convertValidateCleanParameters: ${parameters.toString()}")
    }

    @SuppressWarnings("unchecked")
    private void checkParameterMap(String namePrefix, Map<String, Object> rootParameters, Map<String, Object> parameters,
                                   Map<String, ParameterInfo> parameterInfoMap, ExecutionContextImpl eci) {
        Set<String> defaultCheckSet = new HashSet<>(parameterInfoMap.keySet());
        // have to iterate over a copy of parameters.keySet() as we'll be modifying it
        ArrayList<String> parameterNameList = new ArrayList<>(parameters.keySet());
        int parameterNameListSize = parameterNameList.size();
        for (int i = 0; i < parameterNameListSize; i++) {
            final String parameterName = parameterNameList.get(i);
            ParameterInfo parameterInfo = parameterInfoMap.get(parameterName);
            if (parameterInfo == null) {
                if (validate) {
                    parameters.remove(parameterName);
                    if (logger.isTraceEnabled() && !"ec".equals(parameterName))
                        logger.trace("Parameter [" + namePrefix + parameterName + "] was passed to service [" + serviceName + "] but is not defined as an in parameter, removing from parameters.");
                }
                // even if we are not validating, ie letting extra parameters fall through in this case, we don't want to do the type convert or anything
                continue;
            }

            Object parameterValue = parameters.get(parameterName);

            // set the default if applicable
            boolean parameterIsEmpty = StupidJavaUtilities.isEmpty(parameterValue);
            if (parameterIsEmpty) {
                Object defaultValue = getParameterDefault(parameterInfo, rootParameters, eci);
                if (defaultValue != null) {
                    parameterValue = defaultValue;
                    parameters.put(parameterName, parameterValue);
                    // update parameterIsEmpty now that a default is set
                    parameterIsEmpty = StupidJavaUtilities.isEmpty(parameterValue);
                } else {
                    // if empty but not null and types don't match set to null instead of trying to convert
                    if (parameterValue != null && !parameterInfo.typeMatches(parameterValue)) {
                        parameterValue = null;
                        // put the final parameterValue back into the parameters Map
                        parameters.put(parameterName, null);
                    }
                }
                // if required and still empty (nothing from default), complain
                if (validate && parameterInfo.required && parameterIsEmpty)
                    eci.messageFacade.addValidationError(null, namePrefix + parameterName, serviceName, eci.getL10n().localize("Field cannot be empty"), null);
            }

            if (!parameterIsEmpty) {
                boolean typeMatches = parameterInfo.typeMatches(parameterValue);
                if (!typeMatches) {
                    // convert type, at this point parameterValue is not empty and doesn't match parameter type
                    parameterValue = ServiceJavaUtil.convertType(parameterInfo, namePrefix, parameterName, parameterValue, eci);
                    // put the final parameterValue back into the parameters Map
                    parameters.put(parameterName, parameterValue);
                }

                if (validate) {
                    Object htmlValidated = ServiceJavaUtil.validateParameterHtml(parameterInfo, this, namePrefix, parameterName, parameterValue, eci);
                    // put the final parameterValue back into the parameters Map
                    if (htmlValidated != null) {
                        parameterValue = htmlValidated;
                        parameters.put(parameterName, parameterValue);
                    }

                    // check against validation sub-elements (do this after the convert so we can deal with objects when needed)
                    if (parameterInfo.validationNodeList.size() > 0) {
                        for (MNode valNode : parameterInfo.validationNodeList) {
                            // NOTE don't break on fail, we want to get a list of all failures for the user to see
                            try {
                                // validateParameterSingle calls eci.message.addValidationError as needed so nothing else to do here
                                validateParameterSingle(valNode, parameterName, parameterValue, eci);
                            } catch (Throwable t) {
                                logger.error("Error in validation", t);
                                Map<String, Object> map = new HashMap<>(3);
                                map.put("parameterValue", parameterValue); map.put("valNode", valNode); map.put("t", t);
                                eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${parameterValue}) failed ${valNode.name} validation: ${t.message}", "", map), null);
                            }
                        }
                    }
                }

                // now check parameter sub-elements
                if (parameterInfo.childParameterInfoMap.size() > 0) {
                    if (parameterValue instanceof Map) {
                        // any parameter sub-nodes?
                        checkParameterMap(namePrefix + parameterName + ".", rootParameters, (Map) parameterValue,
                                parameterInfo.childParameterInfoMap, eci);
                    }
                    // this is old code not used and not maintained, but may be useful at some point (note that checkParameterNode also commented below):
                    // } else if (parameterValue instanceof MNode) {
                    //     checkParameterNode("${namePrefix}${parameterName}.", rootParameters, (MNode) parameterValue, parameterInfo.childParameterInfoMap, validate, eci)
                }
            }
            defaultCheckSet.remove(parameterName);
        }

        // now fill in all parameters with defaults (iterate through all parameters not passed)
        for (String parameterName : defaultCheckSet) {
            ParameterInfo parameterInfo = parameterInfoMap.get(parameterName);
            Object defaultValue = getParameterDefault(parameterInfo, rootParameters, eci);
            if (defaultValue != null) {
                if (!StupidJavaUtilities.isInstanceOf(defaultValue, parameterInfo.type))
                    defaultValue = ServiceJavaUtil.convertType(parameterInfo, namePrefix, parameterName, defaultValue, eci);
                parameters.put(parameterName, defaultValue);
            }
        }
    }

    private static Object getParameterDefault(ParameterInfo parameterInfo, Map<String, Object> rootParameters, ExecutionContextImpl eci) {
        String defaultStr = parameterInfo.defaultStr;
        if (defaultStr != null && defaultStr.length() > 0) {
            return eci.getResource().expression(defaultStr, null, rootParameters);
        }

        String defaultValueStr = parameterInfo.defaultValue;
        if (defaultValueStr != null && defaultValueStr.length() > 0) {
            return eci.getResource().expand(defaultValueStr, null, rootParameters, false);
        }

        return null;
    }

    private boolean validateParameterSingle(MNode valNode, String parameterName, Object pv, ExecutionContextImpl eci) {
        // should never be null (caller checks) but check just in case
        if (pv == null) return true;

        String validateName = valNode.getName();
        if ("val-or".equals(validateName)) {
            boolean anyPass = false;
            for (MNode child : valNode.getChildren()) if (validateParameterSingle(child, parameterName, pv, eci)) anyPass = true;
            return anyPass;
        } else if ("val-and".equals(validateName)) {
            boolean allPass = true;
            for (MNode child : valNode.getChildren()) if (!validateParameterSingle(child, parameterName, pv, eci)) allPass = false;
            return allPass;
        } else if ("val-not".equals(validateName)) {
            boolean allPass = true;
            for (MNode child : valNode.getChildren()) if (!validateParameterSingle(child, parameterName, pv, eci)) allPass = false;
            return !allPass;
        } else if ("matches".equals(validateName)) {
            if (!(pv instanceof CharSequence)) {
                Map<String, Object> map = new HashMap<>(1); map.put("pv", pv);
                eci.getMessage().addValidationError(null, parameterName, serviceName,
                        eci.getResource().expand("Value entered (${pv}) is not a string, cannot do matches validation.", "", map), null);
                return false;
            }

            String pvString = pv.toString();
            String regexp = valNode.attribute("regexp");
            if (regexp != null && !regexp.isEmpty() && !pvString.matches(regexp)) {
                // a message attribute should always be there, but just in case we'll have a default
                final String message = valNode.attribute("message");
                Map<String, Object> map = new HashMap<>(2); map.put("pv", pv); map.put("regexp", regexp);
                eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand(message != null && !message.isEmpty() ? message : "Value entered (${pv}) did not match expression: ${regexp}", "", map), null);
                return false;
            }

            return true;
        } else if ("number-range".equals(validateName)) {
            BigDecimal bdVal = new BigDecimal(pv.toString());
            String minStr = valNode.attribute("min");
            if (minStr != null && !minStr.isEmpty()) {
                BigDecimal min = new BigDecimal(minStr);
                if ("false".equals(valNode.attribute("min-include-equals"))) {
                    if (bdVal.compareTo(min) <= 0) {
                        Map<String, Object> map = new HashMap<>(2); map.put("pv", pv); map.put("min", min);
                        eci.getMessage().addValidationError(null, parameterName, serviceName,
                                eci.getResource().expand("Value entered (${pv}) is less than or equal to ${min} must be greater than.", "", map), null);
                        return false;
                    }
                } else {
                    if (bdVal.compareTo(min) < 0) {
                        Map<String, Object> map = new HashMap<>(2); map.put("pv", pv); map.put("min", min);
                        eci.getMessage().addValidationError(null, parameterName, serviceName,
                                eci.getResource().expand("Value entered (${pv}) is less than ${min} and must be greater than or equal to.", "", map), null);
                        return false;
                    }
                }
            }

            String maxStr = valNode.attribute("max");
            if (maxStr != null && !maxStr.isEmpty()) {
                BigDecimal max = new BigDecimal(maxStr);
                if ("true".equals(valNode.attribute("max-include-equals"))) {
                    if (bdVal.compareTo(max) > 0) {
                        Map<String, Object> map = new HashMap<>(2); map.put("pv", pv); map.put("max", max);
                        eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${pv}) is greater than ${max} and must be less than or equal to.", "", map), null);
                        return false;
                    }

                } else {
                    if (bdVal.compareTo(max) >= 0) {
                        Map<String, Object> map = new HashMap<>(2); map.put("pv", pv); map.put("max", max);
                        eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${pv}) is greater than or equal to ${max} and must be less than.", "", map), null);
                        return false;
                    }
                }
            }

            return true;
        } else if ("number-integer".equals(validateName)) {
            try {
                new BigInteger(pv.toString());
            } catch (NumberFormatException e) {
                if (logger.isTraceEnabled())
                    logger.trace("Adding error message for NumberFormatException for BigInteger parse: " + e.toString());
                Map<String, Object> map = new HashMap<>(1); map.put("pv", pv);
                eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value [${pv}] is not a whole (integer) number.", "", map), null);
                return false;
            }

            return true;
        } else if ("number-decimal".equals(validateName)) {
            try {
                new BigDecimal(pv.toString());
            } catch (NumberFormatException e) {
                if (logger.isTraceEnabled())
                    logger.trace("Adding error message for NumberFormatException for BigDecimal parse: " + e.toString());
                Map<String, Object> map = new HashMap<>(1);
                map.put("pv", pv);
                eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value [${pv}] is not a decimal number.", "", map), null);
                return false;
            }

            return true;
        } else if ("text-length".equals(validateName)) {
            String str = pv.toString();
            String minStr = valNode.attribute("min");
            if (minStr != null && !minStr.isEmpty()) {
                int min = Integer.parseInt(minStr);
                if (str.length() < min) {
                    Map<String, Object> map = new HashMap<>(3); map.put("pv", pv); map.put("str", str); map.put("minStr", minStr);
                    eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${pv}), length ${str.length()}, is shorter than ${minStr} characters.", "", map), null);
                    return false;
                }

            }

            String maxStr = valNode.attribute("max");
            if (maxStr != null && !maxStr.isEmpty()) {
                int max = Integer.parseInt(maxStr);
                if (str.length() > max) {
                    Map<String, Object> map = new HashMap<>(3); map.put("pv", pv); map.put("str", str); map.put("maxStr", maxStr);
                    eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${pv}), length ${str.length()}, is longer than ${maxStr} characters.", "", map), null);
                    return false;
                }
            }

            return true;
        } else if ("text-email".equals(validateName)) {
            String str = pv.toString();
            if (!emailValidator.isValid(str)) {
                Map<String, String> map = new HashMap<>(1); map.put("str", str);
                eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${str}) is not a valid email address.", "", map), null);
                return false;
            }

            return true;
        } else if ("text-url".equals(validateName)) {
            String str = pv.toString();
            if (!urlValidator.isValid(str)) {
                Map<String, String> map = new HashMap<>(1); map.put("str", str);
                eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${str}) is not a valid URL.", "", map), null);
                return false;
            }

            return true;
        } else if ("text-letters".equals(validateName)) {
            String str = pv.toString();
            for (char c : str.toCharArray()) {
                if (!Character.isLetter(c)) {
                    Map<String, String> map = new HashMap<>(1); map.put("str", str);
                    eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${str}) must have only letters.", "", map), null);
                    return false;
                }
            }

            return true;
        } else if ("text-digits".equals(validateName)) {
            String str = pv.toString();
            for (char c : str.toCharArray()) {
                if (!Character.isDigit(c)) {
                    Map<String, String> map = new HashMap<>(1); map.put("str", str);
                    eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value [${str}] must have only digits.", "", map), null);
                    return false;
                }
            }

            return true;
        } else if ("time-range".equals(validateName)) {
            Calendar cal;
            String format = valNode.attribute("format");
            if (pv instanceof CharSequence) {
                cal = eci.getL10n().parseDateTime(pv.toString(), format);
            } else {
                // try letting groovy convert it
                cal = Calendar.getInstance();
                // TODO: not sure if this will work: ((pv as java.util.Date).getTime())
                cal.setTimeInMillis((DefaultGroovyMethods.asType(pv, Date.class)).getTime());
            }

            String after = valNode.attribute("after");
            if (after != null && !after.isEmpty()) {
                // handle after date/time/date-time depending on type of parameter, support "now" too
                Calendar compareCal;
                if ("now".equals(after)) {
                    compareCal = Calendar.getInstance();
                    compareCal.setTimeInMillis(eci.getUser().getNowTimestamp().getTime());
                } else {
                    compareCal = eci.getL10n().parseDateTime(after, format);
                }
                if (cal != null && !cal.after(compareCal)) {
                    Map<String, Object> map = new HashMap<>(2); map.put("pv", pv); map.put("after", after);
                    eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${pv}) is before ${after}.", "", map), null);
                    return false;
                }
            }

            String before = valNode.attribute("before");
            if (before != null && !before.isEmpty()) {
                // handle after date/time/date-time depending on type of parameter, support "now" too
                Calendar compareCal;
                if ("now".equals(before)) {
                    compareCal = Calendar.getInstance();
                    compareCal.setTimeInMillis(eci.getUser().getNowTimestamp().getTime());
                } else {
                    compareCal = eci.getL10n().parseDateTime(before, format);
                }
                if (cal != null && !cal.before(compareCal)) {
                    Map<String, Object> map = new HashMap<>(1); map.put("pv", pv);
                    eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${pv}) is after ${before}.", "", map), null);
                    return false;
                }
            }

            return true;
        } else if ("credit-card".equals(validateName)) {
            long creditCardTypes = 0;
            String types = valNode.attribute("types");
            if (types != null && !types.isEmpty()) {
                for (String cts : types.split(",")) creditCardTypes += creditCardTypeMap.get(cts.trim());
            } else {
                creditCardTypes = allCreditCards;
            }

            CreditCardValidator ccv = new CreditCardValidator(creditCardTypes);
            String str = pv.toString();
            if (!ccv.isValid(str)) {
                Map<String, String> map = new HashMap<>(1); map.put("str", str);
                eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${str}) is not a valid credit card number.", "", map), null);
                return false;
            }

            return true;
        }
        // shouldn't get here, but just in case
        return true;
    }

    private static final HashMap<String, Long> creditCardTypeMap;
    static {
        HashMap<String, Long> map = new HashMap<>(5);
        map.put("visa", CreditCardValidator.VISA);
        map.put("mastercard", CreditCardValidator.MASTERCARD);
        map.put("amex", CreditCardValidator.AMEX);
        map.put("discover", CreditCardValidator.DISCOVER);
        map.put("dinersclub", CreditCardValidator.DINERS);
        creditCardTypeMap = map;
    }
    private static final long allCreditCards = CreditCardValidator.VISA + CreditCardValidator.MASTERCARD +
            CreditCardValidator.AMEX + CreditCardValidator.DISCOVER + CreditCardValidator.DINERS;

    private static final HashMap<String, ArtifactExecutionInfo.AuthzAction> verbAuthzActionEnumMap;
    static {
        HashMap<String, ArtifactExecutionInfo.AuthzAction> map = new HashMap<>(6);
        map.put("create", ArtifactExecutionInfo.AUTHZA_CREATE);
        map.put("update", ArtifactExecutionInfo.AUTHZA_UPDATE);
        map.put("store", ArtifactExecutionInfo.AUTHZA_UPDATE);
        map.put("delete", ArtifactExecutionInfo.AUTHZA_DELETE);
        map.put("view", ArtifactExecutionInfo.AUTHZA_VIEW);
        map.put("find", ArtifactExecutionInfo.AUTHZA_VIEW);
        verbAuthzActionEnumMap = map;
    }
}
