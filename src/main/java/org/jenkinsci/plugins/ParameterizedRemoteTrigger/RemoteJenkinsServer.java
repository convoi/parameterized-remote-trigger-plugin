package org.jenkinsci.plugins.ParameterizedRemoteTrigger;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import net.sf.json.JSONObject;

import net.sf.json.JSONSerializer;
import net.sf.json.util.JSONUtils;
import org.apache.commons.codec.binary.Base64;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.CopyOnWriteList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

/**
 * Holds everything regarding the remote server we wish to connect to, including validations and what not.
 * 
 * @author Maurice W.
 * 
 */
public class RemoteJenkinsServer extends AbstractDescribableImpl<RemoteJenkinsServer> {

    private final URL             address;
    private final String          displayName;
    private final boolean         hasBuildTokenRootSupport;
    private final String          username;
    private final String          apiToken;

    private CopyOnWriteList<Auth> auth = new CopyOnWriteList<Auth>();

    @DataBoundConstructor
    public RemoteJenkinsServer(String address, String displayName, boolean hasBuildTokenRootSupport, JSONObject auth)
            throws MalformedURLException {

        this.address = new URL(address);
        this.displayName = displayName.trim();
        this.hasBuildTokenRootSupport = hasBuildTokenRootSupport;

        // Holding on to both of these variables for legacy purposes. The seemingly 'dirty' getters for these properties
        // are for the same reason.
        this.username = "";
        this.apiToken = "";

        // this.auth = new Auth(auth);
        this.auth.replaceBy(new Auth(auth));

    }

    // Getters

    public Auth[] getAuth() {
        return auth.toArray(new Auth[this.auth.size()]);
    }

    public String getDisplayName() {
        String displayName = null;

        if (this.displayName == null || this.displayName.trim().equals("")) {
            displayName = this.getAddress().toString();
        } else {
            displayName = this.displayName;
        }
        return displayName;
    }

    public URL getAddress() {
        return address;
    }

    public boolean getHasBuildTokenRootSupport() {
        return this.hasBuildTokenRootSupport;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    String getJobInfoUrl(String jobName) {
        return getBaseJobUrl(jobName) + "/api/json";
    }

    String getBaseJobUrl(String job) {
        String urlString = getAddress().toString();
        urlString += "/job/";
        urlString += UrlUtils.encodeValue(job);
        return urlString;
    }

    JSONObject doJsonGetRequest(String urlString, String requestType, AbstractBuild build,
            BuildListener listener, int numberOfAttempts,
            RemoteBuildConfiguration remoteBuildConfiguration)
            throws IOException, InterruptedException, MacroEvaluationException {

        JSONObject responseObject = null;

        URL buildUrl = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) buildUrl.openConnection();

        // if there is a username + apiToken defined for this remote host, then use it
        String usernameTokenConcat;

        if (remoteBuildConfiguration.getOverrideAuth()) {
            usernameTokenConcat = remoteBuildConfiguration.getAuth()[0].getUsername() + ":" + remoteBuildConfiguration
                    .getAuth()[0].getPassword();
        } else {
            usernameTokenConcat = getAuth()[0].getUsername() + ":"
                    + getAuth()[0].getPassword();
        }

        if (!usernameTokenConcat.equals(":")) {
            // token-macro replacment
            usernameTokenConcat = TokenMacro.expandAll(build, listener, usernameTokenConcat);

            byte[] encodedAuthKey = Base64.encodeBase64(usernameTokenConcat.getBytes());
            connection.setRequestProperty("Authorization", "Basic " + new String(encodedAuthKey));
        }

        try {
            connection.setDoInput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestMethod(requestType);
            // wait up to 5 seconds for the connection to be open
            connection.setConnectTimeout(5000);
            connection.connect();

            InputStream is;
            try {
                is = connection.getInputStream();
            } catch (FileNotFoundException e) {
                // In case of a e.g. 404 status
                is = connection.getErrorStream();
            }

            BufferedReader rd = new BufferedReader(new InputStreamReader(is));
            String line;
            // String response = "";
            StringBuilder response = new StringBuilder();

            while ((line = rd.readLine()) != null) {
                response.append(line);
            }
            rd.close();

            // JSONSerializer serializer = new JSONSerializer();
            // need to parse the data we get back into struct
            //listener.getLogger().println("Called URL: '" + urlString +  "', got response: '" + response.toString() + "'");

            //Solving issue reported in this comment: https://github.com/jenkinsci/parameterized-remote-trigger-plugin/pull/3#issuecomment-39369194
            //Seems like in Jenkins version 1.547, when using "/build" (job API for non-parameterized jobs), it returns a string indicating the status.
            //But in newer versions of Jenkins, it just returns an empty response.
            //So we need to compensate and check for both.
            if (!JSONUtils.mayBeJSON(response.toString())) {
                listener.getLogger().println("Remote Jenkins server returned empty response or invalid JSON - but we can still proceed with the remote build.");
                return null;
            } else {
                responseObject = (JSONObject) JSONSerializer.toJSON(response.toString());
            }

        } catch (IOException e) {
            listener.getLogger().println(e.getMessage());
            //If we have connectionRetryLimit set to > 0 then retry that many times.
            if( numberOfAttempts <= remoteBuildConfiguration.getConnectionRetryLimit()) {
                listener.getLogger().println("Connection to remote server failed, waiting for to retry - " + remoteBuildConfiguration.getPollInterval() + " seconds until next attempt.");
                e.printStackTrace();

                // Sleep for 'pollInterval' seconds.
                // Sleep takes miliseconds so need to convert this.pollInterval to milisecopnds (x 1000)
                // Could do with a better way of sleeping...
                Thread.sleep(remoteBuildConfiguration.getPollInterval() * 1000);

                listener.getLogger().println("Retry attempt #" + numberOfAttempts + " out of " + remoteBuildConfiguration.getConnectionRetryLimit());
                responseObject = remoteBuildConfiguration
                        .sendHTTPCall(urlString, requestType, build, listener, numberOfAttempts + 1);
            }else{
                //reached the maximum number of retries, time to fail
                throw new RuntimeException("Max number of connection retries have been exeeded.");
            }

        } finally {
            // always make sure we close the connection
            if (connection != null) {
                connection.disconnect();
            }
            // and always clear the query string and remove some "global" values
            remoteBuildConfiguration.clearQueryString();
            // this.build = null;
            // this.listener = null;

        }
        return responseObject;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<RemoteJenkinsServer> {

        private JSONObject authenticationMode;

        /**
         * In order to load the persisted global configuration, you have to call load() in the constructor.
         */
        /*
         * public DescriptorImpl() { load(); }
         */

        public String getDisplayName() {
            return "";
        }

        public ListBoxModel doFillCredsItems() {
            // StandardUsernameListBoxModel model = new StandardUsernameListBoxModel();

            // Item item = Stapler.getCurrentRequest().findAncestorObject(Item.class);
            // model.withAll(CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class, item, ACL.SYSTEM,
            // Collections.<DomainRequirement>emptyList()));

            return Auth.DescriptorImpl.doFillCredsItems();

            // return model;
        }

        public JSONObject doFillAuthenticationMode() {
            return this.authenticationMode.getJSONObject("authenticationType");
        }

        /**
         * Validates the given address to see that it's well-formed, and is reachable.
         * 
         * @param address
         *            Remote address to be validated
         * @return FormValidation object
         */
        public FormValidation doValidateAddress(@QueryParameter String address) {

            URL host = null;

            // no empty addresses allowed
            if (address == null || address.trim().equals("")) {
                return FormValidation.error("The remote address can not be left empty.");
            }

            // check if we have a valid, well-formed URL
            try {
                host = new URL(address);
                URI uri = host.toURI();
            } catch (Exception e) {
                return FormValidation.error("Malformed address (" + address + "), please double-check it.");
            }

            // check that the host is reachable
            try {
                HttpURLConnection connection = (HttpURLConnection) host.openConnection();
                connection.setConnectTimeout(5000);
                connection.connect();
            } catch (Exception e) {
                return FormValidation.warning("Address looks good, but we were not able to connect to it");
            }

            return FormValidation.okWithMarkup("Address looks good");
        }
    }

}
