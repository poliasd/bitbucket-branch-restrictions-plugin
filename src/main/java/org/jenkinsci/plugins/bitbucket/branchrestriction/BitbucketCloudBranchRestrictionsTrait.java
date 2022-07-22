package org.jenkinsci.plugins.bitbucket.branchrestriction;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.jenkins.plugins.bitbucket.BitbucketGitSCMBuilder;
import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMSource;
import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMSourceContext;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.damnhandy.uri.template.UriTemplate;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.plugins.git.GitSCM;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMBuilder;
import jenkins.scm.api.trait.SCMSourceContext;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import org.apache.http.HttpHost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.time.temporal.ChronoUnit.SECONDS;


public class BitbucketCloudBranchRestrictionsTrait extends SCMSourceTrait {
    private static final String V2_API_BASE_URL = "https://api.bitbucket.org/2.0/repositories";

    private static final HttpHost API_HOST = HttpHost.create("https://api.bitbucket.org");

    private static final Logger LOGGER = Logger.getLogger(BitbucketCloudBranchRestrictionsTrait.class.getName());

    private static final PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();

    private HttpClientContext context;
    private String  authenticationHeader;

    @NonNull
    private final String branchPermissions;
    private final String prAccessUsers;
    private final String prAccessGroups;
    private final String reviewersGeneralId;
    private final String reviewersDefaultId;
    private final String successfulBuildsId;
    private final boolean generalApproval;
    private final boolean defaultReviewersApproval;
    private final boolean successfulBuilds;

    private String repoOwner;
    private String repository;

    @DataBoundConstructor
    public BitbucketCloudBranchRestrictionsTrait(@NonNull String branchPermissions,
                                                 String prAccessUsers,
                                                 String prAccessGroups,
                                                 String reviewersGeneralId,
                                                 String reviewersDefaultId,
                                                 String successfulBuildsId,
                                                 boolean generalApproval,
                                                 boolean defaultReviewersApproval,
                                                 boolean successfulBuilds
                                               ) {
        this.branchPermissions = branchPermissions;
        this.prAccessUsers = prAccessUsers;
        this.prAccessGroups = prAccessGroups;
        this.reviewersGeneralId = reviewersGeneralId;
        this.reviewersDefaultId = reviewersDefaultId;
        this.successfulBuildsId = successfulBuildsId;
        this.generalApproval = generalApproval;
        this.defaultReviewersApproval = defaultReviewersApproval;
        this.successfulBuilds = successfulBuilds;
    }

    @Override
    protected void decorateBuilder(SCMBuilder<?, ?> builder) {
        GitSCM gitSCM = ((BitbucketGitSCMBuilder) builder).build();
        BitbucketSCMSource scmSource = ((BitbucketGitSCMBuilder) builder).scmSource();

        repoOwner = scmSource.getRepoOwner();
        repository = scmSource.getRepository();

        String[] users = prAccessUsers.split(",");
        String userJson = "";
        if (!(prAccessUsers.equals("Everybody") || prAccessUsers.equals("") )) {
            for (String user: users) {
                if (user.trim().equals("Everybody")) {
                    user = "";
                }
                userJson = userJson + "{\"username\": \"" + user.trim() + "\" }";
            }
        }

        String[] groups = prAccessGroups.split(",");
        String groupJson = "";
        if (!(prAccessGroups.equals("Everybody") || prAccessGroups.equals("") )) {
            for (String group : groups) {
                if (group.trim().equals("Everybody")) {
                    group = "";
                }
                groupJson = groupJson + "{\"slug\": \"" + group.trim() + "\" }";
            }
        }
        String restrictMergeJson = "{\"kind\":\"restrict_merges\",\"branch_match_kind\":\"glob\",\"pattern\":\"" + branchPermissions +
                "\",\"users\":["+ userJson +"],\"groups\":[" + groupJson + "]}";
        String defaultReviewerApprovalsJson = "{ \"kind\": \"require_default_reviewer_approvals_to_merge\", \"value\": " + this.reviewersDefaultId + ", \"branch_match_kind\": \"glob\", \"pattern\": \"" + branchPermissions + "\" }";
        String reviewerApprovalsJson = "{ \"kind\": \"require_approvals_to_merge\", \"value\": " + this.reviewersGeneralId + ", \"branch_match_kind\": \"glob\", \"pattern\": \"" + branchPermissions + "\" }";
        String passingBuildsJson  = "{ \"kind\": \"require_passing_builds_to_merge\", \"value\": " + this.successfulBuildsId + ", \"branch_match_kind\": \"glob\", \"pattern\": \"" + branchPermissions + "\" }";

        String url = UriTemplate.fromTemplate("https://api.bitbucket.org/2.0/repositories{/repoOwner,repository}/branch-restrictions")
                .set("repoOwner", scmSource.getRepoOwner())
                .set("repository", scmSource.getRepository())
                .expand();

        StandardUsernamePasswordCredentials creds;

        creds = CredentialsMatchers.firstOrNull(CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class,
                        Jenkins.get(), null, Collections.<DomainRequirement>emptyList()),
                CredentialsMatchers.withId(scmSource.getCredentialsId()));
        authenticationHeader = "Basic " + Base64.getEncoder().encodeToString((creds.getUsername() + ":" + creds.getPassword()).getBytes());

        HttpResponse<String> restrictMerge = sendPostRequest(url, restrictMergeJson);
        if (restrictMerge.statusCode() == 409) {
            String respBody = restrictMerge.body();
            String id = respBody.substring(respBody.indexOf("id=") + 3, respBody.indexOf(")\""));
            sendPutRequest(url, restrictMergeJson, id);
        } else if (restrictMerge.statusCode() != 201){
            LOGGER.log(Level.SEVERE, "Something went worng during the execution of the request: "+ restrictMerge.body());
        }
        if (defaultReviewersApproval) {
            HttpResponse<String> defaultReviewerApprovals = sendPostRequest(url, defaultReviewerApprovalsJson);
            if (defaultReviewerApprovals.statusCode() == 409) {
                String respBody = defaultReviewerApprovals.body();
                String idDRA = respBody.substring(respBody.indexOf("id=") + 3, respBody.indexOf(")\""));
                sendPutRequest(url, defaultReviewerApprovalsJson, idDRA);
            } else if (defaultReviewerApprovals.statusCode() != 201){
                LOGGER.log(Level.SEVERE, "Something went worng during the execution of the request: "+ defaultReviewerApprovals.body());
            }
        }
        if (generalApproval) {
            HttpResponse<String> reviewerApprovals = sendPostRequest(url, reviewerApprovalsJson);
            if (reviewerApprovals.statusCode() == 409) {
                String respBody = reviewerApprovals.body();
                String idRA = respBody.substring(respBody.indexOf("id=") + 3, respBody.indexOf(")\""));
                sendPutRequest(url, reviewerApprovalsJson, idRA);
            } else if (reviewerApprovals.statusCode() != 201){
                LOGGER.log(Level.SEVERE, "Something went worng during the execution of the request: "+ reviewerApprovals.body());
            }
        }
        if (successfulBuilds) {
            HttpResponse<String> passingBuilds = sendPostRequest(url, passingBuildsJson);
            if (passingBuilds.statusCode() == 409) {
                String respBody = passingBuilds.body();
                String idPB = respBody.substring(respBody.indexOf("id=") + 3, respBody.indexOf(")\""));
                sendPutRequest(url, passingBuildsJson, idPB);
            } else if (passingBuilds.statusCode() != 201){
                LOGGER.log(Level.SEVERE, "Something went worng during the execution of the request: "+ passingBuilds.body());
            }
        }
    }

    private HttpResponse<String> sendPostRequest(String url, String payload) {
        HttpResponse<String> response = null;
        try {
            SSLContext context;
            try {
                context = SSLContext.getInstance("TLSv1.3");
                context.init(null, null, null);
            } catch (GeneralSecurityException e) {
                throw new RuntimeException(e);
            }
            HttpClient client = HttpClient.newBuilder().sslContext(context).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", authenticationHeader)
                    .version(HttpClient.Version.HTTP_1_1)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(Duration.of(30, SECONDS))
                    .build();
            response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).get();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Cannot execute request {0}",e.getCause());
        }
        return response;
    }

    private HttpResponse<String> sendPutRequest(String url, String payload, String id) {
        HttpResponse<String> response = null;
        try {
            SSLContext context;
            try {
                context = SSLContext.getInstance("TLSv1.3");
                context.init(null, null, null);
            } catch (GeneralSecurityException e) {
                throw new RuntimeException(e);
            }
            HttpClient client = HttpClient.newBuilder().sslContext(context).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/" + id))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", authenticationHeader)
                    .version(HttpClient.Version.HTTP_1_1)
                    .PUT(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(Duration.of(30, SECONDS))
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Cannot execute request {0}",e.getCause());
        }
        return response;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Symbol("BitbucketBuildStatusNotifications")
    @Extension
    public static class DescriptorImpl extends SCMSourceTraitDescriptor {

        /**
         * {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            return Messages.BitbucketCloudBranchRestrictionsTrait_displayName();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Class<? extends SCMSourceContext> getContextClass() {
            return BitbucketSCMSourceContext.class;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Class<? extends SCMSource> getSourceClass() {
            return BitbucketSCMSource.class;
        }

        @NonNull
        @Restricted(NoExternalUse.class)
        @SuppressWarnings("unused") // stapler
        public ListBoxModel doFillReviewersGeneralIdItems() {
            ListBoxModel result = new ListBoxModel();
            for (int i=1; i<10; i++) {
                result.add(String.valueOf(i), String.valueOf(i));
            }
            return result;
        }

        @NonNull
        @Restricted(NoExternalUse.class)
        @SuppressWarnings("unused") // stapler
        public ListBoxModel doFillReviewersDefaultIdItems() {
            ListBoxModel result = new ListBoxModel();
            for (int i=1; i<10; i++) {
                result.add(String.valueOf(i), String.valueOf(i));
            }
            return result;
        }

        @NonNull
        @Restricted(NoExternalUse.class)
        @SuppressWarnings("unused")
        public ListBoxModel doFillSuccessfulBuildsIdItems() {
            ListBoxModel result = new ListBoxModel();
            for (int i=1; i<20; i++) {
                result.add(String.valueOf(i), String.valueOf(i));
            }
            return result;
        }
    }

}
