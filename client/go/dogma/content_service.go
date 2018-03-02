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
	"context"
	"fmt"
	"net/http"
	"strings"
)

type contentService service

// Query specifies a query on a file.
type Query struct {
	Path string
	// QueryType can be "identity" or "json_path". "identity" is used to retrieve the content as it is.
	// "json_path" applies a series of JSON path to the content.
	// See https://github.com/json-path/JsonPath/blob/master/README.md
	QueryType   string
	Expressions []string
}

// Entry represents an entry in the repository.
type Entry struct {
	Path       string      `json:"path"`
	Type       string      `json:"type"` // can be JSON, TEXT or DIRECTORY
	Content    interface{} `json:"content,omitempty"`
	Revision   string      `json:"revision,omitempty"`
	URL        string      `json:"url,omitempty"`
	ModifiedAt string      `json:"modifiedAt,omitempty"`
}

// Commit represents a commit in the repository.
type Commit struct {
	Revision      int            `json:"revision"`
	Author        string         `json:"author"`
	CommitMessage *CommitMessage `json:"commitMessage,omitempty"`
	PushedAt      string         `json:"pushedAt,omitempty"`
	Entries       []*Entry       `json:"entries,omitempty"`
}

// CommitMessages represents a commit message in the repository.
type CommitMessage struct {
	Summary string `json:"summary"`
	Detail  string `json:"detail,omitempty"`
	Markup  string `json:"markup,omitempty"`
}

// Change represents a change to commit in the repository.
type Change struct {
	Path string `json:"path"`

	// can be UPSERT_JSON, UPSERT_TEXT, REMOVE, RENAME, APPLY_JSON_PATCH or APPLY_TEXT_PATCH
	Type    string      `json:"type"`
	Content interface{} `json:"content,omitempty"`
}

func (con *contentService) listFiles(ctx context.Context,
	projectName, repoName, revision, pathPattern string) ([]*Entry, *http.Response, error) {
	u := fmt.Sprintf("%vprojects/%v/repos/%v/tree%v", defaultPathPrefix, projectName, repoName, pathPattern)

	if len(revision) != 0 {
		u += "?revision=" + revision
	}

	req, err := con.client.newRequest("GET", u, nil)
	if err != nil {
		return nil, nil, err
	}

	var entries []*Entry
	res, err := con.client.do(ctx, req, &entries)
	if err != nil {
		return nil, res, err
	}
	return entries, res, nil
}

func (con *contentService) getFile(
	ctx context.Context, projectName, repoName, revision string, query *Query) (*Entry, *http.Response, error) {
	path := query.Path
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}

	u := fmt.Sprintf("%vprojects/%v/repos/%v/contents%v", defaultPathPrefix, projectName, repoName, path)
	urlQuery, err := getFileURLQuery(revision, path, query.Expressions)
	if err != nil {
		return nil, nil, err
	}
	u += urlQuery

	req, err := con.client.newRequest("GET", u, nil)
	if err != nil {
		return nil, nil, err
	}

	entry := new(Entry)
	res, err := con.client.do(ctx, req, entry)
	if err != nil {
		return nil, res, err
	}

	return entry, res, nil
}

// getFileURLQuery currently only supports JSON path.
func getFileURLQuery(revision, path string, jsonPaths []string) (urlQuery string, err error) {
	if len(jsonPaths) != 0 {
		if urlQuery, err = getJSONPaths(path, jsonPaths); err != nil {
			return "", err
		}

		if len(revision) != 0 {
			// have both of the jsonpath and the revision
			urlQuery += "&revision=" + revision
		}
	} else if len(revision) != 0 {
		// have the revision only
		urlQuery += "?revision=" + revision
	}
	return urlQuery, nil
}

func getJSONPaths(path string, jsonPaths []string) (urlQuery string, err error) {
	if !strings.HasSuffix(strings.ToLower(path), "json") {
		return "", fmt.Errorf("the extension of the file should be .json (path: %v)", path)
	}
	for i, jsonPath := range jsonPaths {
		if i == 0 {
			urlQuery = "?"
		} else {
			urlQuery += "&"
		}
		urlQuery += fmt.Sprintf("jsonpath=%v", jsonPath)
	}
	// the urlQuery starts with '?'
	return urlQuery, nil
}

func (con *contentService) getFiles(ctx context.Context,
	projectName, repoName, revision, pathPattern string) ([]*Entry, *http.Response, error) {
	u := fmt.Sprintf("%vprojects/%v/repos/%v/contents%v", defaultPathPrefix, projectName, repoName, pathPattern)

	if len(revision) != 0 {
		u += "?revision=" + revision
	}

	req, err := con.client.newRequest("GET", u, nil)
	if err != nil {
		return nil, nil, err
	}

	var entries []*Entry
	res, err := con.client.do(ctx, req, &entries)
	if err != nil {
		return nil, res, err
	}
	return entries, res, nil
}

func (con *contentService) getHistory(ctx context.Context,
	projectName, repoName, from, to, pathPattern string) ([]*Commit, *http.Response, error) {
	u := fmt.Sprintf("%vprojects/%v/repos/%v/commits/%v", defaultPathPrefix, projectName, repoName, from)

	urlQuery := ""
	if len(pathPattern) != 0 {
		urlQuery = fmt.Sprintf("?path=%v", pathPattern)
		if len(to) != 0 {
			urlQuery += fmt.Sprintf("&to=%v", to)
		}
	} else if len(to) != 0 {
		urlQuery = fmt.Sprintf("?to=%v", to)
	}
	u += urlQuery

	req, err := con.client.newRequest("GET", u, nil)
	if err != nil {
		return nil, nil, err
	}

	var commits []*Commit
	res, err := con.client.do(ctx, req, &commits)
	if err != nil {
		return nil, res, err
	}
	return commits, res, nil
}

func (con *contentService) getDiff(ctx context.Context,
	projectName, repoName, from, to string, query *Query) (*Change, *http.Response, error) {
	path := query.Path
	if len(path) == 0 {
		return nil, nil, fmt.Errorf("the path should not be empty")
	}
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}

	u := fmt.Sprintf("%vprojects/%v/repos/%v/compare", defaultPathPrefix, projectName, repoName)
	urlQuery, err := getDiffURLQuery(from, to, path, query.Expressions)
	if err != nil {
		return nil, nil, err
	}
	u += urlQuery

	req, err := con.client.newRequest("GET", u, nil)
	if err != nil {
		return nil, nil, err
	}

	change := new(Change)
	res, err := con.client.do(ctx, req, change)
	if err != nil {
		return nil, res, err
	}

	return change, res, nil
}

// getDiffURLQuery currently only supports JSON path.
func getDiffURLQuery(from, to, path string, jsonPaths []string) (urlQuery string, err error) {
	if len(jsonPaths) != 0 {
		if urlQuery, err = getJSONPaths(path, jsonPaths); err != nil {
			return "", err
		}
	}

	if !strings.HasPrefix(urlQuery, "?") {
		urlQuery = fmt.Sprintf("?path=%v", path)
	} else {
		urlQuery += fmt.Sprintf("&path=%v", path)
	}

	if len(from) != 0 {
		urlQuery += fmt.Sprintf("&from=%v", from)
	}

	if len(to) != 0 {
		urlQuery += fmt.Sprintf("&to=%v", to)
	}

	return urlQuery, nil
}

func (con *contentService) getDiffs(ctx context.Context,
	projectName, repoName, from, to, pathPattern string) ([]*Change, *http.Response, error) {
	if len(pathPattern) == 0 {
		pathPattern = "/**"
	}

	u := fmt.Sprintf("%vprojects/%v/repos/%v/compare", defaultPathPrefix, projectName, repoName)
	urlQuery, err := getDiffURLQuery(from, to, pathPattern, nil)
	if err != nil {
		return nil, nil, err
	}
	u += urlQuery

	req, err := con.client.newRequest("GET", u, nil)
	if err != nil {
		return nil, nil, err
	}

	var changes []*Change
	res, err := con.client.do(ctx, req, &changes)
	if err != nil {
		return nil, res, err
	}
	return changes, res, nil
}

type Push struct {
	CommitMessage *CommitMessage `json:"commitMessage"`
	Changes       []Change       `json:"changes"`
}

func (con *contentService) push(ctx context.Context, projectName, repoName, baseRevision string,
	commitMessage CommitMessage, changes []Change) (*Commit, *http.Response, error) {
	if len(commitMessage.Summary) == 0 {
		return nil, nil, fmt.Errorf(
			"summary of commitMessage cannot be empty. commitMessage: %+v", commitMessage)
	}

	if len(changes) == 0 {
		return nil, nil, fmt.Errorf("no changes to commit")
	}

	u := fmt.Sprintf("%vprojects/%v/repos/%v/contents", defaultPathPrefix, projectName, repoName)

	if len(baseRevision) != 0 {
		u += fmt.Sprintf("?revision=%v", baseRevision)
	}

	body := Push{CommitMessage: &commitMessage, Changes: changes}

	req, err := con.client.newRequest("POST", u, body)
	if err != nil {
		return nil, nil, err
	}

	commit := new(Commit)
	res, err := con.client.do(ctx, req, commit)
	if err != nil {
		return nil, res, err
	}
	return commit, res, nil
}
