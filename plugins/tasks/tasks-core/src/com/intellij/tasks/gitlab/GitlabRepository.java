package com.intellij.tasks.gitlab;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.util.Comparing;
import com.intellij.tasks.Task;
import com.intellij.tasks.TaskBundle;
import com.intellij.tasks.TaskRepositoryType;
import com.intellij.tasks.gitlab.model.GitlabIssue;
import com.intellij.tasks.gitlab.model.GitlabProject;
import com.intellij.tasks.impl.gson.GsonUtil;
import com.intellij.tasks.impl.httpclient.NewBaseRepositoryImpl;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.xmlb.annotations.Tag;
import com.intellij.util.xmlb.annotations.Transient;
import org.apache.http.*;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.protocol.HttpContext;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static com.intellij.tasks.impl.httpclient.ResponseUtil.GsonMultipleObjectsDeserializer;
import static com.intellij.tasks.impl.httpclient.ResponseUtil.GsonSingleObjectDeserializer;

/**
 * @author Mikhail Golubev
 */
@Tag("Gitlab")
public class GitlabRepository extends NewBaseRepositoryImpl {

  @NonNls public static final String REST_API_PATH_PREFIX = "/api/v3/";
  private static final Pattern ID_PATTERN = Pattern.compile("\\d+");

  public static final Gson GSON = GsonUtil.createDefaultBuilder().create();
  public static final TypeToken<List<GitlabProject>> LIST_OF_PROJECTS_TYPE = new TypeToken<List<GitlabProject>>() {
  };
  public static final TypeToken<List<GitlabIssue>> LIST_OF_ISSUES_TYPE = new TypeToken<List<GitlabIssue>>() {
  };
  public static final GitlabProject UNSPECIFIED_PROJECT = new GitlabProject() {
    @Override
    public String getName() {
      return "-- from all projects --";
    }

    @Override
    public int getId() {
      return -1;
    }
  };
  private GitlabProject myCurrentProject;
  private List<GitlabProject> myProjects = null;

  /**
   * Serialization constructor
   */
  @SuppressWarnings("UnusedDeclaration")
  public GitlabRepository() {
  }

  /**
   * Normal instantiation constructor
   */
  public GitlabRepository(TaskRepositoryType type) {
    super(type);
  }

  /**
   * Cloning constructor
   */
  public GitlabRepository(GitlabRepository other) {
    super(other);
    myCurrentProject = other.myCurrentProject;
  }

  @Override
  public boolean equals(Object o) {
    if (!super.equals(o)) return false;
    GitlabRepository repository = (GitlabRepository)o;
    if (!Comparing.equal(myCurrentProject, repository.myCurrentProject)) return false;
    return true;
  }

  @NotNull
  @Override
  public GitlabRepository clone() {
    return new GitlabRepository(this);
  }

  @Override
  public Task[] getIssues(@Nullable String query, int offset, int limit, boolean withClosed) throws Exception {
    return ContainerUtil.map2Array(fetchIssues((offset / limit) + 1, limit), GitlabTask.class, new Function<GitlabIssue, GitlabTask>() {
      @Override
      public GitlabTask fun(GitlabIssue issue) {
        return new GitlabTask(GitlabRepository.this, issue);
      }
    });
  }

  @Nullable
  @Override
  public Task findTask(@NotNull String id) throws Exception {
    // doesn't work now, because Gitlab's REST API doesn't provide endpoint to find task
    // by its global ID, only by project ID and task's local ID (iid).
    //GitlabIssue issue = fetchIssue(Integer.parseInt(id));
    //return issue == null ? null : new GitlabTask(this, issue);
    return null;
  }

  @Nullable
  @Override
  public CancellableConnection createCancellableConnection() {
    return new CancellableConnection() {
      private HttpGet myRequest = new HttpGet(getIssuesUrl());

      @Override
      protected void doTest() throws Exception {
        HttpResponse response = getHttpClient().execute(myRequest);
        StatusLine statusLine = response.getStatusLine();
        if (statusLine != null && statusLine.getStatusCode() != HttpStatus.SC_OK) {
          throw new Exception(TaskBundle.message("failure.http.error", statusLine.getStatusCode(), statusLine.getReasonPhrase()));
        }
      }

      // TODO: find more about proper request aborting in HttpClient4.x
      @Override
      public void cancel() {
        myRequest.abort();
      }
    };
  }

  /**
   * Always forcibly attempts do fetch new projects from server.
   */
  @NotNull
  public List<GitlabProject> fetchProjects() throws Exception {
    HttpGet request = new HttpGet(getRestApiUrl("projects"));
    ResponseHandler<List<GitlabProject>> handler = new GsonMultipleObjectsDeserializer<GitlabProject>(GSON, LIST_OF_PROJECTS_TYPE);
    myProjects = getHttpClient().execute(request, handler);
    return Collections.unmodifiableList(myProjects);
  }

  @SuppressWarnings("UnusedDeclaration")
  @NotNull
  public GitlabProject fetchProject(int id) throws Exception {
    HttpGet request = new HttpGet(getRestApiUrl("project", id));
    return getHttpClient().execute(request, new GsonSingleObjectDeserializer<GitlabProject>(GSON, GitlabProject.class));
  }

  @NotNull
  public List<GitlabIssue> fetchIssues(int pageNumber, int pageSize) throws Exception {
    ensureProjectsDiscovered();
    ResponseHandler<List<GitlabIssue>> handler = new GsonMultipleObjectsDeserializer<GitlabIssue>(GSON, LIST_OF_ISSUES_TYPE);
    return getHttpClient().execute(new HttpGet(getIssuesUrl()), handler);
  }

  private String getIssuesUrl() {
    if (myCurrentProject != null && myCurrentProject != UNSPECIFIED_PROJECT) {
      return getRestApiUrl("projects", myCurrentProject.getId(), "issues");
    }
    return getRestApiUrl("issues");
  }

  @SuppressWarnings("UnusedDeclaration")
  @Nullable
  public GitlabIssue fetchIssue(int id) throws Exception {
    ensureProjectsDiscovered();
    HttpGet request = new HttpGet(getRestApiUrl("issues", id));
    ResponseHandler<GitlabIssue> handler = new GsonSingleObjectDeserializer<GitlabIssue>(GSON, GitlabIssue.class);
    return getHttpClient().execute(request, handler);
  }

  @Override
  public String getPresentableName() {
    String name = getUrl();
    if (myCurrentProject != null && myCurrentProject != UNSPECIFIED_PROJECT) {
      name += "/" + myCurrentProject.getName();
    }
    return name;
  }

  @Nullable
  @Override
  public String extractId(@NotNull String taskName) {
    return ID_PATTERN.matcher(taskName).matches() ? taskName : null;
  }

  @Override
  public boolean isConfigured() {
    return super.isConfigured() && !myPassword.isEmpty();
  }

  @NotNull
  @Override
  public String getRestApiPathPrefix() {
    return REST_API_PATH_PREFIX;
  }

  @Nullable
  @Override
  protected HttpRequestInterceptor createRequestInterceptor() {
    return new HttpRequestInterceptor() {
      @Override
      public void process(HttpRequest request, HttpContext context) throws HttpException, IOException {
        request.addHeader("PRIVATE-TOKEN", myPassword);
        //request.addHeader("Accept", "application/json");
      }
    };
  }

  public void setCurrentProject(@Nullable GitlabProject project) {
    myCurrentProject = project != null && project.getId() == -1 ? UNSPECIFIED_PROJECT : project;
  }

  public GitlabProject getCurrentProject() {
    return myCurrentProject;
  }

  /**
   * May return cached projects or make request to receive new ones.
   */
  @NotNull
  public List<GitlabProject> getProjects() {
    try {
      ensureProjectsDiscovered();
    }
    catch (Exception ignored) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(myProjects);
  }

  private void ensureProjectsDiscovered() throws Exception {
    if (myProjects == null) {
      fetchProjects();
    }
  }

  @TestOnly
  @Transient
  public void setProjects(@NotNull List<GitlabProject> projects) {
    myProjects = projects;
  }
}
