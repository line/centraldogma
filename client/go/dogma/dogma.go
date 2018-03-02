// Copyright 2018 LINE Corporation
//
// LINE Corporation licenses this file to you under the Apache License,
// version 2.0 (the "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at:
//
//   https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations
// under the License.

package dogma

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"golang.org/x/oauth2/clientcredentials"
	"io"
	"io/ioutil"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

const (
	DefaultPort = 36462

	defaultScheme       = "http"
	defaultHostName     = "localhost"
	defaultPathPrefix   = "api/v1/"
	defaultBaseURL      = defaultScheme + "://" + defaultHostName + ":36462/"
	pathSecurityEnabled = "security_enabled"
)

// A Client communicates with the Central Dogma server API.
type Client struct {
	client *http.Client // HTTP client which sends the request.

	BaseURL *url.URL // Base URL for API requests.

	// Services are used to communicate for the different parts of the Central Dogma server API.
	project    *projectService
	repository *repositoryService
	content    *contentService
}

type service struct {
	client *Client
}

func NewClient(baseURL string, config clientcredentials.Config) (*Client, error) {
	return NewClientWithHTTPClient(baseURL, config.Client(context.Background()))
}

func NewClientWithHTTPClient(baseURL string, client *http.Client) (*Client, error) {
	var normalizedURL *url.URL
	var err error

	if len(baseURL) != 0 {
		if normalizedURL, err = normalizeURL(baseURL); err != nil {
			return nil, err
		}
	} else {
		normalizedURL, err = url.Parse(defaultBaseURL)
	}

	c := &Client{
		client:  client,
		BaseURL: normalizedURL,
	}
	service := &service{client: c}

	c.project = (*projectService)(service)
	c.repository = (*repositoryService)(service)
	c.content = (*contentService)(service)
	return c, nil
}

func normalizeURL(baseURL string) (*url.URL, error) {
	if !strings.HasPrefix(baseURL, "http") {
		// Prepend the defaultScheme when there is no specified scheme so parse the url properly
		// in case of "hostname:port".
		baseURL = defaultScheme + "://" + baseURL
	}

	parsedURL, err := url.Parse(baseURL)
	if err != nil {
		return nil, err
	}
	if len(parsedURL.Scheme) == 0 {
		baseURL = defaultScheme + "://" + baseURL
	}
	port := parsedURL.Port()
	if len(port) == 0 {
		baseURL += ":" + strconv.Itoa(DefaultPort)
	}
	if !strings.HasSuffix(baseURL, "/") {
		baseURL += "/"
	}
	return url.Parse(baseURL)
}

// SecurityEnabled returns whether the security of the server is enabled or not.
func (c *Client) SecurityEnabled() (bool, error) {
	u, err := c.BaseURL.Parse(pathSecurityEnabled)
	if err != nil {
		return false, err
	}

	req := &http.Request{Method: http.MethodGet, URL: u}
	res, err := c.client.Do(req)
	if err != nil {
		return false, err
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		return false, fmt.Errorf("authenticaion failed (status: %s)", res.Status)
	}
	b, _ := ioutil.ReadAll(res.Body)
	if string(b) == "true" {
		return true, nil
	}
	return false, nil
}

func (c *Client) newRequest(method, urlStr string, body interface{}) (*http.Request, error) {
	u, err := c.BaseURL.Parse(urlStr)
	if err != nil {
		return nil, err
	}

	var buf io.ReadWriter
	if body != nil {
		if str, ok := body.(string); ok {
			buf = bytes.NewBufferString(str)
		} else {
			buf = new(bytes.Buffer)
			enc := json.NewEncoder(buf)
			//enc.SetEscapeHTML(true)
			err := enc.Encode(body)
			if err != nil {
				return nil, err
			}
		}
	}

	req, err := http.NewRequest(method, u.String(), buf)
	if err != nil {
		return nil, err
	}

	if auth := req.Header.Get("Authorization"); len(auth) == 0 {
		req.Header.Set("Authorization", "Bearer anonymous")
	}

	if body != nil {
		if method == "PATCH" {
			req.Header.Set("Content-Type", "application/json-patch+json")
		} else {
			req.Header.Set("Content-Type", "application/json")
		}
	}
	return req, nil
}

func (c *Client) do(ctx context.Context, req *http.Request, resContent interface{}) (*http.Response, error) {
	req = req.WithContext(ctx)

	res, err := c.client.Do(req)
	if err != nil {
		fmt.Println(err)
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
		}
	}
	defer res.Body.Close()

	if resContent != nil {
		err = json.NewDecoder(res.Body).Decode(resContent)
		if err == io.EOF { // empty response body
			err = nil
		}
	}

	return res, err
}

// CreateProject creates a project.
func (c *Client) CreateProject(ctx context.Context, name string) (*Project, *http.Response, error) {
	return c.project.create(ctx, name)
}

// RemoveProject removes a project. A removed project can be unremoved using UnremoveProject.
func (c *Client) RemoveProject(ctx context.Context, name string) (*http.Response, error) {
	return c.project.remove(ctx, name)
}

// UnremoveProject unremoves a removed project.
func (c *Client) UnremoveProject(ctx context.Context, name string) (*Project, *http.Response, error) {
	return c.project.unremove(ctx, name)
}

// ListProjects returns the list of projects.
func (c *Client) ListProjects(ctx context.Context) ([]*Project, *http.Response, error) {
	return c.project.list(ctx)
}

// ListRemovedProjects returns the list of removed projects.
func (c *Client) ListRemovedProjects(ctx context.Context) ([]*Project, *http.Response, error) {
	return c.project.listRemoved(ctx)
}

// CreateRepository creates a repository.
func (c *Client) CreateRepository(
	ctx context.Context, projectName, repoName string) (*Repository, *http.Response, error) {
	return c.repository.create(ctx, projectName, repoName)
}

// RemoveRepository removes a repository. A removed repository can be unremoved using UnremoveRepository.
func (c *Client) RemoveRepository(ctx context.Context, projectName, repoName string) (*http.Response, error) {
	return c.repository.remove(ctx, projectName, repoName)
}

// UnremoveRepository unremoves a repository.
func (c *Client) UnremoveRepository(
	ctx context.Context, projectName, repoName string) (*Repository, *http.Response, error) {
	return c.repository.unremove(ctx, projectName, repoName)
}

// ListRepositories returns the list of repositories.
func (c *Client) ListRepositories(
	ctx context.Context, projectName string) ([]*Repository, *http.Response, error) {
	return c.repository.list(ctx, projectName)
}

// ListRemovedRepositories returns the list of the removed repositories which can be unremoved using
// UnremoveRepository.
func (c *Client) ListRemovedRepositories(
	ctx context.Context, projectName string) ([]*Repository, *http.Response, error) {
	return c.repository.listRemoved(ctx, projectName)
}

// NormalizeRevision converts the relative revision number to the absolute revision number(e.g. -1 -> 3).
func (c *Client) NormalizeRevision(
	ctx context.Context, projectName, repoName, revision string) (int, *http.Response, error) {
	return c.repository.normalizeRevision(ctx, projectName, repoName, revision)
}

// ListFiles returns the list of files that match the given path pattern. A path pattern is a variant of glob:
//
//     - "/**": find all files recursively
//     - "*.json": find all JSON files recursively
//     - "/foo/*.json": find all JSON files under the directory /foo
//     - "/&#42;/foo.txt": find all files named foo.txt at the second depth level
//     - "*.json,/bar/*.txt": use comma to match any patterns
//
func (c *Client) ListFiles(ctx context.Context,
	projectName, repoName, revision, pathPattern string) ([]*Entry, *http.Response, error) {
	return c.content.listFiles(ctx, projectName, repoName, revision, pathPattern)
}

// GetFile returns the file at the specified revision and path with the specified Query.
func (c *Client) GetFile(
	ctx context.Context, projectName, repoName, revision string, query *Query) (*Entry, *http.Response, error) {
	return c.content.getFile(ctx, projectName, repoName, revision, query)
}

// GetFiles returns the files that match the given path pattern. A path pattern is a variant of glob:
//
//     - "/**": find all files recursively
//     - "*.json": find all JSON files recursively
//     - "/foo/*.json": find all JSON files under the directory /foo
//     - "/&#42;/foo.txt": find all files named foo.txt at the second depth level
//     - "*.json,/bar/*.txt": use comma to match any patterns
//
func (c *Client) GetFiles(ctx context.Context,
	projectName, repoName, revision, pathPattern string) ([]*Entry, *http.Response, error) {
	return c.content.getFiles(ctx, projectName, repoName, revision, pathPattern)
}

// GetHistory returns the history of the files that match the given path pattern. A path pattern is
// a variant of glob:
//
//     - "/**": find all files recursively
//     - "*.json": find all JSON files recursively
//     - "/foo/*.json": find all JSON files under the directory /foo
//     - "/&#42;/foo.txt": find all files named foo.txt at the second depth level
//     - "*.json,/bar/*.txt": use comma to match any patterns
//
// If the from and to are not specified, this will return the history from the init to the latest revision.
func (c *Client) GetHistory(ctx context.Context,
	projectName, repoName, from, to, pathPattern string) ([]*Commit, *http.Response, error) {
	return c.content.getHistory(ctx, projectName, repoName, from, to, pathPattern)
}

// GetDiff returns the diff of a file between two revisions. If the from and to are not specified, this will
// return the diff from the init to the latest revision.
func (c *Client) GetDiff(ctx context.Context,
	projectName, repoName, from, to string, query *Query) (*Change, *http.Response, error) {
	return c.content.getDiff(ctx, projectName, repoName, from, to, query)
}

// GetDiffs returns the diffs of the files that match the given path pattern. A path pattern is
// a variant of glob:
//
//     - "/**": find all files recursively
//     - "*.json": find all JSON files recursively
//     - "/foo/*.json": find all JSON files under the directory /foo
//     - "/&#42;/foo.txt": find all files named foo.txt at the second depth level
//     - "*.json,/bar/*.txt": use comma to match any patterns
//
// If the from and to are not specified, this will return the diffs from the init to the latest revision.
func (c *Client) GetDiffs(ctx context.Context,
	projectName, repoName, from, to, pathPattern string) ([]*Change, *http.Response, error) {
	return c.content.getDiffs(ctx, projectName, repoName, from, to, pathPattern)
}

// Push pushes the specified changes to the repository.
func (c *Client) Push(ctx context.Context, projectName, repoName, baseRevision string,
	commitMessage CommitMessage, changes ...Change) (*Commit, *http.Response, error) {
	return c.content.push(ctx, projectName, repoName, baseRevision, commitMessage, changes)
}
