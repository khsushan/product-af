package org.wso2.carbon.appfactory.jenkins;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.axis2.AxisFault;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.client.Options;
import org.apache.axis2.client.ServiceClient;
import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.neethi.Policy;
import org.apache.neethi.PolicyBuilder;
import org.apache.neethi.PolicyEngine;
import org.apache.rampart.RampartMessageData;
import org.jaxen.JaxenException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.wso2.carbon.appfactory.application.deployer.stub.ApplicationDeployerAppFactoryExceptionException;
import org.wso2.carbon.appfactory.jenkins.artifact.storage.Utils;
import org.wso2.carbon.appfactory.jenkins.build.notify.JenkinsCIBuildStatusReceiverClient;
import org.wso2.carbon.appfactory.jenkins.build.stub.xsd.BuildStatusBean;
import org.wso2.carbon.appfactory.jenkins.util.JenkinsUtility;
import org.wso2.carbon.appfactory.application.deployer.stub.ApplicationDeployerStub;
import org.wso2.carbon.appfactory.common.AppFactoryConstants;
import org.wso2.carbon.appfactory.common.AppFactoryException;
import org.wso2.carbon.appfactory.core.Deployer;
import org.wso2.carbon.utils.CarbonUtils;

import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The plugin for storing build artifact permanently, deploy artifacts and
 * notify appfactory with build status
 */
public class AppfactoryPluginManager extends Notifier implements Serializable {
    private static final Log log = LogFactory.getLog(AppfactoryPluginManager.class);
    // these are the parameters that we defined in config.jelly
    private final String applicationId;
    private final String applicationVersion;
    private final String applicationArtifactExtension;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public AppfactoryPluginManager(String applicationId, String applicationVersion,
                                   String applicationArtifactExtension) {
        this.applicationId = applicationId;
        this.applicationVersion = applicationVersion;
        this.applicationArtifactExtension = applicationArtifactExtension;
    }

//    {
//        System.setProperty("javax.net.ssl.trustStore", getDescriptor().getClientTrustStore());
//        System.setProperty("javax.net.ssl.trustStorePassword", getDescriptor().
//                getClientTrustStorePassword());
//    }

    /**
     * When the user configures the project and enables this Notifier, when a build is performed
     * this method will be invoked by jenkins
     * This method is mainly used to notify appfactory regarding the build status and
     * store the tagged artifacts by user
     * @param build
     * @param launcher
     * @param listener
     * @return
     * @throws InterruptedException
     * @throws IOException
     */
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        PrintStream logger = listener.getLogger();

        final String APPFACTORY_SERVER_URL = getDescriptor().getAppfactoryServerURL();
        String serviceURL = APPFACTORY_SERVER_URL + "/services/JenkinsCIBuildStatusRecieverService";
        JenkinsCIBuildStatusReceiverClient client = new JenkinsCIBuildStatusReceiverClient
                (serviceURL, getDescriptor().getAdminUserName(), getDescriptor().getAdminPassword());
        BuildStatusBean buildStatus = createBuildStatusBean(build.getNumber());

//        Getting the tenant user name from the build parameters because we are sending it from AF
        String tenantUserName = build.getBuildVariables().get("tenantUserName");

        if (build.getResult() == Result.SUCCESS) {
            buildStatus.setBuildSuccessful(true);
            buildStatus.setLogMsg("Build Successful");
            client.onBuildCompletion(buildStatus, tenantUserName);

            boolean isAutomatic = Boolean.parseBoolean(build.getEnvironment(listener).
                    get("isAutomatic"));
            if (isAutomatic) {
                try {
                    String stage = getStage(applicationId,applicationVersion);
                    // this block is executed when a commit goes to a branch where auto deployment is enabled
                    // so this is to deploy the app to the relevant stage. so deployAction = deploy

                    sendMessageToDeploy(applicationId, applicationVersion, "HEAD", stage,
                            AppFactoryConstants.DEPLOY_ACTION_LABEL_ARTIFACT);
                } catch (ApplicationDeployerAppFactoryExceptionException e) {
                    logger.append("Error while retrieving the deployment stage of application ")
                            .append(applicationId).append(" in version ").append
                            (applicationVersion);
                    logger.append("Failed to deploy the artifact of automatic build");
                }
            }
            //String deployAction = build.getEnvironment(listener).get("doDeploy");
            boolean doDeploy = Boolean.parseBoolean(build.getEnvironment(listener).get("doDeploy"));
            if (doDeploy) {
                String tagName = build.getEnvironment(listener).get("tagName");
                String stage = build.getEnvironment(listener).get("deployStage");
                String deployAction = AppFactoryConstants.DEPLOY_ACTION_LABEL_ARTIFACT;
                if(tagName != null && tagName != "") {
                    logger.append("sending message to deploy to stage ").append(stage).append(" with tag ").
                            append(tagName);
                    sendMessageToDeploy(applicationId, applicationVersion, "HEAD", stage, deployAction);
                } else {
                    logger.append("sending message to deploy to stage ").append(stage);
                    sendMessageToDeploy(applicationId, applicationVersion, "HEAD", stage, deployAction);
                }
            } else {
                logger.append("DoDeploy is false");
            }
            logger.append("Successfully finished ").append(build.getFullDisplayName());

        } else {
            if(Boolean.parseBoolean(build.getEnvironment(listener).get("isAutomatic"))) {
                // todo send a notification to appfactory saying the deployment failed
                logger.append("This automatic build will not deploy any artifacts since the build" +
                              " failed for application ").append(applicationId).append(" in " +
                              "version " +applicationVersion);
            }
            buildStatus.setBuildSuccessful(false);
            //TODO get the error message from jenkins and put
            buildStatus.setLogMsg("Build Failed");
            client.onBuildCompletion(buildStatus, tenantUserName);
            logger.append("Build failed ").append(build.getFullDisplayName());
        }

        boolean shouldPersist = Boolean.parseBoolean(build.getEnvironment(listener).
                get("persistArtifact"));
        if (shouldPersist) {
            String tagName = build.getEnvironment(listener).get("tagName");

            if(tagName != null && !tagName.equals("")) {
                logger.append("Storing artifact permanently with the tag name ").append(tagName);

//                We look for all the files which match the given extension
                FilePath[] files = build.getWorkspace().list("**/*." + this.applicationArtifactExtension);

                if (files != null && files.length > 0) {
//                    Using the old logic. There should only be 1 artifact.
//                    Taking the first and ignoring the rest
                    FilePath sourceFilePath = files[0];

//                    We need to create the target folder the file should be copied
                    String filePath = this.getDescriptor().getStoragePath()
                            + File.separator + build.getEnvironment(listener).get("JOB_NAME") +
                            File.separator + tagName;
                    File persistArtifact = new File(filePath);

                    if (!persistArtifact.mkdirs()) {
                        listener.getLogger().append("Unable to create the tag directory");
                    }

//                    Creating the target file path.
                    String targetFilePath = persistArtifact.getAbsolutePath() + File.separator +
                            sourceFilePath.getName();

//                    This works for both the mater and slave builds
                    sourceFilePath.copyTo(new FilePath(new File(targetFilePath)));
                } else {
                    listener.getLogger().append("No artifacts were found to persist");
                }

            } else {
                logger.append("Cannot persist the artifact. Tag name cannot be null or empty");
            }

        } else {
            logger.append("Artifact is not stored permanently. persistArtifact = ").
                    append(String.valueOf(shouldPersist));
        }
        return true;
    }

    private BuildStatusBean createBuildStatusBean(int buildId) {
        BuildStatusBean buildStatus = new BuildStatusBean();
        buildStatus.setApplicationId(applicationId);
        buildStatus.setVersion(applicationVersion);
        buildStatus.setArtifactType(applicationArtifactExtension);
        buildStatus.setBuildId(String.valueOf(buildId));
        return buildStatus;
    }

    private String getStage(String applicationId, String applicationVersion)
            throws ApplicationDeployerAppFactoryExceptionException, RemoteException {

        String applicationDeployerEPR = getDescriptor().getAppfactoryServerURL() +
                                        "/services/ApplicationDeployer";
        ApplicationDeployerStub clientStub = new ApplicationDeployerStub(applicationDeployerEPR);
        ServiceClient deployerClient = clientStub._getServiceClient();
        CarbonUtils.setBasicAccessSecurityHeaders(getDescriptor().getAdminUserName(),
                                                  getDescriptor().getAdminPassword(),
                                                  deployerClient);

        String deployStage = clientStub.getStage(applicationId, applicationVersion);
        return deployStage;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Descriptor for this plugin(represents server wide configs)
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String deployBpelEPR;
        private String adminUserName;
        private String adminPassword;
        private String clientTrustStore;
        private String clientTrustStorePassword;
        private String appfactoryServerURL;
        private String storagePath;
        private String tempPath;
       // private String jenkinsHome;String applicationId, String artifactType, String stage, String tenantDomainString applicationId, String artifactType, String stage, String tenantDomain

        public DescriptorImpl() {
            super(AppfactoryPluginManager.class);
            load();
        }

        public void setDeployBpelEPR(String deployBpelEPR) {
            this.deployBpelEPR = deployBpelEPR;
        }

        public void setAdminUserName(String adminUserName) {
            this.adminUserName = adminUserName;
        }

        public void setAdminPassword(String adminPassword) {
            this.adminPassword = adminPassword;
        }

        /**
         * Performs on-the-fly validation of the form field 'storagePath'
         * Check whether the given storage path is a valid directory
         * @param value value entered by the user
         * @return
         * @throws IOException
         * @throws ServletException
         */
        @SuppressWarnings("unused")
        public FormValidation doCheckStoragePath(@QueryParameter String value) throws
                                                                               IOException,
                                                                               ServletException {
            if (value.length() == 0) {
                return FormValidation.error("Please set required fields");
            }

            File path = new File(value);
            FormValidation formValidation;

            if (path.isDirectory()) {
                formValidation = FormValidation.ok();
            } else {
                formValidation = FormValidation.error("Invalid directory specified");
            }
            return formValidation;
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project
            // types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Appfactory Plugin";
        }

        /**
         * This method is used to configure the values defined in global.jelly
         * @param req
         * @param formData
         * @return
         * @throws FormException
         */
        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {

            setDeployBpelEPR(formData.getString("deployBpelEPR"));
            setAdminUserName(formData.getString("adminUserName"));
            setAdminPassword(formData.getString("adminPassword"));
            setClientTrustStore(formData.getString("clientTrustStore"));
            setClientTrustStorePassword(formData.getString("clientTrustStorePassword"));
            setAppfactoryServerURL(formData.getString("appfactoryServerURL"));
            setStoragePath(formData.getString("storagePath"));
            setTempPath(formData.getString("tempPath"));

            //To persist global configuration information
            save();
            return super.configure(req, formData);
        }

        public String getDeployBpelEPR() {
            return deployBpelEPR;
        }

        public String getAdminUserName() {
            return adminUserName;
        }

        public String getAdminPassword() {
            return adminPassword;
        }

        public String getClientTrustStore() {
            return clientTrustStore;
        }

        public void setClientTrustStore(String clientTrustStore) {
            this.clientTrustStore = clientTrustStore;
        }

        public String getClientTrustStorePassword() {
            return clientTrustStorePassword;
        }

        public void setClientTrustStorePassword(String clientTrustStorePassword) {
            this.clientTrustStorePassword = clientTrustStorePassword;
        }

        public String getAppfactoryServerURL() {
            return appfactoryServerURL;
        }

        public void setAppfactoryServerURL(String appfactoryServerURL) {
            this.appfactoryServerURL = appfactoryServerURL;
        }

        public String getStoragePath() {
            return storagePath;
        }

        public void setStoragePath(String storagePath) {
            //store without File separator at the end for consistency
            if(storagePath.endsWith("/") | storagePath.endsWith("\\")) {
                storagePath = storagePath.substring(0,storagePath.length()-1) ;
            }
            this.storagePath = storagePath;
        }

        public String getTempPath() {
            return tempPath;
        }

        public void setTempPath(String tempPath) {
            this.tempPath = tempPath;
        }
    }

    /**
     * Sending a request to DeployArtifact
     * @param applicationId the application id
     * @param version the version of the application
     * @param revision the revision of the build
     * @param stage stage of the application
     * @param tagName tag name
     */
    public void sendMessageToDeploy(final String applicationId,
                                    final String version, final String revision, final String stage, 
                                    final String deployAction) {
        Thread clientCaller=new Thread(new Runnable() {
            public void run() {
            	String className = null;
            	String appType = null;
            	String jobName = JenkinsUtility.getJobName(applicationId, version);
                try {
                	appType = Utils.getJobConfigElement(jobName);
                	className = Utils.getDeployerClassName(stage, appType);
                } catch (AppFactoryException e) {
                	log.error("Error while calling deploy.",e);
				} catch (JaxenException e) {
					log.error("Error while calling deploy.",e);
				} catch (FileNotFoundException e) {
					log.error("Error while calling deploy.",e);
				} catch (NamingException e) {
					log.error("Error while calling deploy.",e);
				} catch (XMLStreamException e) {
					log.error("Error while calling deploy.",e);
				}
                Deployer deployer;
                Map<String, String[]> parameters = new HashMap<String, String[]>();
                parameters.put(AppFactoryConstants.ARTIFACT_TYPE, new String[]{appType});
                //parameters.put("jobName", new String[]{jobName});
                String applicationId = JenkinsUtility.getApplicationId(jobName);
                String version = JenkinsUtility.getVersion(jobName);
                parameters.put(AppFactoryConstants.APPLICATION_ID, new String[]{applicationId});
                parameters.put(AppFactoryConstants.APPLICATION_VERSION, new String[]{version});
                parameters.put(AppFactoryConstants.DEPLOY_STAGE, new String[]{stage});
                parameters.put(AppFactoryConstants.DEPLOY_ACTION, new String[]{deployAction});
            	try {
                    ClassLoader loader = getClass().getClassLoader();
                    Class<?> customCodeClass = Class.forName(className, true, loader);
                    deployer = (Deployer) customCodeClass.newInstance();
                    //Wait for some time for lastsucessful build symlink appears
                    Thread.sleep(1000);
                    deployer.deployLatestSuccessArtifact(parameters);
                } catch (ClassNotFoundException e) {
                    log.error(e);
                } catch (InstantiationException e) {
                    log.error(e);
                } catch (IllegalAccessException e) {
                    log.error(e);
                } catch (Exception e) {
                    log.error(e);
                }
            }
        }) ;
        clientCaller.start();
    }
    
    

    private Policy getPolicy() throws XMLStreamException {
        String policyString =
                "<wsp:Policy xmlns:wsp=\"http://schemas.xmlsoap.org/ws/2004/09/policy\"\n" +
                        "\t\t\txmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\"\n" +
                        "\t\t\twsu:Id=\"UTOverTransport\">\n" +
                        "\t\t\t<wsp:ExactlyOne>\n" +
                        "\t\t\t\t<wsp:All>\n" +
                        "\t\t\t\t\t<sp:TransportBinding\n" +
                        "\t\t\t\t\t\txmlns:sp=\"http://schemas.xmlsoap.org/ws/2005/07/securitypolicy\">\n" +
                        "\t\t\t\t\t\t<wsp:Policy>\n" +
                        "\t\t\t\t\t\t\t<sp:TransportToken>\n" +
                        "\t\t\t\t\t\t\t\t<wsp:Policy>\n" +
                        "\t\t\t\t\t\t\t\t\t<sp:HttpsToken RequireClientCertificate=\"false\" />\n" +
                        "\t\t\t\t\t\t\t\t</wsp:Policy>\n" +
                        "\t\t\t\t\t\t\t</sp:TransportToken>\n" +
                        "\t\t\t\t\t\t\t<sp:AlgorithmSuite>\n" +
                        "\t\t\t\t\t\t\t\t<wsp:Policy>\n" +
                        "\t\t\t\t\t\t\t\t\t<sp:Basic256 />\n" +
                        "\t\t\t\t\t\t\t\t</wsp:Policy>\n" +
                        "\t\t\t\t\t\t\t</sp:AlgorithmSuite>\n" +
                        "\t\t\t\t\t\t\t<sp:Layout>\n" +
                        "\t\t\t\t\t\t\t\t<wsp:Policy>\n" +
                        "\t\t\t\t\t\t\t\t\t<sp:Lax />\n" +
                        "\t\t\t\t\t\t\t\t</wsp:Policy>\n" +
                        "\t\t\t\t\t\t\t</sp:Layout>\n" +
                        "\t\t\t\t\t\t\t<sp:IncludeTimestamp />\n" +
                        "\t\t\t\t\t\t</wsp:Policy>\n" +
                        "\t\t\t\t\t</sp:TransportBinding>\n" +
                        "\t\t\t\t\t<sp:SignedSupportingTokens\n" +
                        "\t\t\t\t\t\txmlns:sp=\"http://schemas.xmlsoap.org/ws/2005/07/securitypolicy\">\n" +
                        "\t\t\t\t\t\t<wsp:Policy>\n" +
                        "\t\t\t\t\t\t\t<sp:UsernameToken\n" +
                        "\t\t\t\t\t\t\t\tsp:IncludeToken=\"http://schemas.xmlsoap.org/ws/2005/07/securitypolicy/IncludeToken/AlwaysToRecipient\" />\n" +
                        "\t\t\t\t\t\t</wsp:Policy>\n" +
                        "\t\t\t\t\t</sp:SignedSupportingTokens>\n" +
                        "\t\t\t\t\t<rampart:RampartConfig xmlns:rampart=\"http://ws.apache.org/rampart/policy\">\n" +
                        "\t\t\t\t\t\t<rampart:encryptionUser>useReqSigCert</rampart:encryptionUser>\n" +
                        "\t\t\t\t\t\t<rampart:timestampPrecisionInMilliseconds>true</rampart:timestampPrecisionInMilliseconds>\n" +
                        "\t\t\t\t\t\t<rampart:timestampTTL>300</rampart:timestampTTL>\n" +
                        "\t\t\t\t\t\t<rampart:timestampMaxSkew>300</rampart:timestampMaxSkew>\n" +
                        "\t\t\t\t\t\t<rampart:timestampStrict>false</rampart:timestampStrict>\n" +
                        "\t\t\t\t\t\t<rampart:passwordCallbackClass>org.wso2.carbon.appfactory.utilities.security.PWCBHandler</rampart:passwordCallbackClass>\n" +
                        "\t\t\t\t\t\t<rampart:tokenStoreClass>org.wso2.carbon.security.util.SecurityTokenStore</rampart:tokenStoreClass>\n" +
                        "\t\t\t\t\t\t<rampart:nonceLifeTime>300</rampart:nonceLifeTime>\n" +
                        "\t\t\t\t\t</rampart:RampartConfig>\n" +
                        "\t\t\t\t</wsp:All>\n" +
                        "\t\t\t</wsp:ExactlyOne>\n" +
                        "\t\t</wsp:Policy>";

        return PolicyEngine.getPolicy(AXIOMUtil.stringToOM(policyString));
    }

    private static OMElement getPayload(String applicationId, String version, String revision,
                                        String stage, String tagName, String deployAction, boolean isAutomatic)
            throws XMLStreamException {

        String payload;
        if (tagName == null) {
            payload = "<p:DeployArtifactRequest xmlns:p=\"http://wso2.org\">" +
                      "<p:applicationId>" + applicationId + "</p:applicationId>" +
                      "<p:revision>" + revision + "</p:revision>" +
                      "<p:version>" + version + "</p:version>" +
                      "<p:stage>" + stage + "</p:stage>" +
                      "<p:build>true</p:build>" +
                      "<p:tagName></p:tagName>" +
                      "<p:deployAction>" + deployAction + "</p:deployAction>" +
                      "<p:automaticDeployment>" + Boolean.toString(isAutomatic) + "</p:automaticDeployment>" +
                      "</p:DeployArtifactRequest>";
        } else {
            payload = "<p:DeployArtifactRequest xmlns:p=\"http://wso2.org\">" +
                      "<p:applicationId>" + applicationId + "</p:applicationId>" +
                      "<p:revision>" + revision + "</p:revision>" +
                      "<p:version>" + version + "</p:version>" +
                      "<p:stage>" + stage + "</p:stage>" +
                      "<p:build>true</p:build>" +
                      "<p:tagName>" + tagName + "</p:tagName>" +
                      "<p:deployAction>" + deployAction + "</p:deployAction>" +
                      "<p:automaticDeployment>" + Boolean.toString(isAutomatic) + "</p:automaticDeployment>" +
                      "</p:DeployArtifactRequest>";
        }
        return new StAXOMBuilder(new ByteArrayInputStream(payload.getBytes())).getDocumentElement();
    }
}