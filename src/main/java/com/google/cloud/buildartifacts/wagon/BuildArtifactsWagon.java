/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.buildartifacts.wagon;


import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpStatusCodes;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.maven.wagon.AbstractWagon;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.repository.Repository;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.apache.maven.wagon.resource.Resource;


public final class BuildArtifactsWagon extends AbstractWagon {

  private GoogleRepository googleRepository;
  private HttpRequestFactory requestFactory;
  private boolean hasCredentials;
  private static final String HOST = "maven.pkg.dev";

  private InputStream getInputStream(Resource resource)
      throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
    try {
      GenericUrl url = googleRepository.constructURL(HOST, resource.getName());
      HttpRequest request = requestFactory.buildGetRequest(url);
      HttpResponse response = request.execute();
      return response.getContent();
    } catch (HttpResponseException e) {
      rethrowKnownStatusCodes(e);
      throw new TransferFailedException("Received an error from the remote server.", e);
    } catch (IOException e) {
      throw new TransferFailedException("Failed to send request to remote server.", e);
    }
  }

  @Override
  protected void openConnectionInternal() throws ConnectionException, AuthenticationException {
    HttpRequestInitializer requestInitializer;
    HttpTransport httpTransport = new NetHttpTransport();
    try {
      GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
      requestInitializer = new HttpCredentialsAdapter(credentials);
      requestFactory = httpTransport.createRequestFactory(requestInitializer);
      hasCredentials = true;
    } catch (IOException e) {
      requestFactory = httpTransport.createRequestFactory();
    }
    googleRepository = GoogleRepository.parse(repository);
  }

  @Override
  protected void closeConnection() throws ConnectionException {

  }

  @Override
  public void get(String resourceName, File destination)
      throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
    getIfNewer(resourceName, destination, 0);
  }

  @Override
  public boolean getIfNewer(String resourceName, File destination, long timestamp)
      throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
    Resource resource = new Resource(resourceName);
    this.fireGetInitiated(resource, destination);
    try {
      this.fireGetStarted(resource, destination);
      InputStream input = getInputStream(resource);
      this.getTransfer(resource, destination, input);
      this.fireGetCompleted(resource, destination);
    } catch (Exception e) {
      this.fireTransferError(resource, e, TransferEvent.REQUEST_GET);
      throw e;
    }
    return true;
  }

  private void handlePutRequest(File source, Resource resource, GenericUrl url)
      throws AuthorizationException, ResourceDoesNotExistException, TransferFailedException {
    try {
      HttpRequest request = requestFactory.buildPutRequest(url, new HttpContent() {
        @Override
        public long getLength() throws IOException {
          return source.length();
        }

        @Override
        public String getType() {
          return null;
        }

        @Override
        public boolean retrySupported() {
          return true;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
          try {
            putTransfer(resource, source, out, false);
          } catch (TransferFailedException | AuthorizationException | ResourceDoesNotExistException e) {
            throw new FileTransferException(e);
          }
        }
      });
      request.execute();
    } catch (HttpResponseException e) {
      rethrowKnownStatusCodes(e);
      throw new TransferFailedException("Received an error from the remote server.", e);
    } catch (FileTransferException e) {
      Throwable cause = e.getCause();
      if (cause instanceof TransferFailedException) {
        throw (TransferFailedException) cause;
      } else if (cause instanceof AuthorizationException) {
        throw (AuthorizationException) cause;
      } else if (cause instanceof ResourceDoesNotExistException) {
        throw (ResourceDoesNotExistException) cause;
      }
      throw new TransferFailedException("Error uploading file.", cause);
    } catch (IOException e) {
      throw new TransferFailedException("Failed to send request to remote server.", e);
    }

  }

  @Override
  public void put(File source, String destination)
      throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
    Resource resource = new Resource(destination);
    this.firePutInitiated(resource, source);
    resource.setContentLength(source.length());
    resource.setLastModified(source.lastModified());
    GenericUrl url = googleRepository.constructURL(HOST, resource.getName());
    this.firePutStarted(resource, source);
    handlePutRequest(source, resource, url);
    this.firePutCompleted(resource, source);
  }

  private void rethrowKnownStatusCodes(HttpResponseException e)
      throws AuthorizationException, ResourceDoesNotExistException {
    if (e.getStatusCode() == HttpStatusCodes.STATUS_CODE_FORBIDDEN
        || e.getStatusCode() == HttpStatusCodes.STATUS_CODE_UNAUTHORIZED) {
      StringBuilder builder = new StringBuilder();
      builder.append("Permission denied on remote repository (or it may not exist). ");
      if (!hasCredentials) {
        builder
            .append("The request had no credentials because the application default credentials ");
        builder.append(
            "are not available. See https://developers.google.com/accounts/docs/application-default-credentials for more information.");
      }
      throw new AuthorizationException(builder.toString(), e);
    } else if (e.getStatusCode() == HttpStatusCodes.STATUS_CODE_NOT_FOUND) {
      throw new ResourceDoesNotExistException("The remote resource does not exist.", e);
    }
  }

  private static class FileTransferException extends IOException {

    FileTransferException(Throwable cause) {
      super(cause);
    }
  }

  private static class GoogleRepository {

    private final String projectId;
    private final String repositoryId;

    GoogleRepository(String projectId, String repositoryId) {
      this.projectId = projectId;
      this.repositoryId = repositoryId;
    }

    static GoogleRepository parse(Repository repo) throws ConnectionException {
      ConnectionException invalidFormatException = new ConnectionException(
          "The repository URL must be formatted as buildartifacts://projects/<project_id>/repositories/<repository_id>");
      if (!repo.getHost().equals("projects")) {
        throw invalidFormatException;
      }
      String[] parts = repo.getBasedir().split("/");
      if (parts.length != 4) {
        throw invalidFormatException;
      }
      if (!parts[2].equals("repositories") || !parts[0].isEmpty()) {
        throw invalidFormatException;
      }
      return new GoogleRepository(parts[1], parts[3]);
    }

    GenericUrl constructURL(String host, String artifactPath) {
      GenericUrl url = new GenericUrl();
      url.setScheme("https");
      url.setHost(host + "/");
      List<String> segments = new ArrayList<>();
      segments.add(projectId);
      segments.add(repositoryId);
      segments.addAll(Arrays.asList(artifactPath.split("/")));
      url.setPathParts(segments);
      return url;
    }
  }
}