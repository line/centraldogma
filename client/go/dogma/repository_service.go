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
)

type repositoryService service

// Repository represents a repository in the Central Dogma server.
type Repository struct {
	Name         string  `json:"name"`
	Creator      *Author `json:"creator,omitempty"`
	HeadRevision int     `json:"headRevision,omitempty"`
	URL          string  `json:"url,omitempty"`
	CreatedAt    string  `json:"createdAt,omitempty"`
}

func (r *repositoryService) create(ctx context.Context, projectName, repoName string) (*Repository,
	*http.Response, error) {
	u := fmt.Sprintf("%vprojects/%v/repos", defaultPathPrefix, projectName)

	req, err := r.client.newRequest(http.MethodPost, u, &Repository{Name: repoName})
	if err != nil {
		return nil, nil, err
	}

	repo := new(Repository)
	res, err := r.client.do(ctx, req, repo)
	if err != nil {
		return nil, res, err
	}

	return repo, res, nil
}

func (r *repositoryService) remove(ctx context.Context, projectName, repoName string) (*http.Response, error) {
	u := fmt.Sprintf("%vprojects/%v/repos/%v", defaultPathPrefix, projectName, repoName)

	req, err := r.client.newRequest(http.MethodDelete, u, nil)
	if err != nil {
		return nil, err
	}

	res, err := r.client.do(ctx, req, nil)
	if err != nil {
		return res, err
	}
	return res, nil
}

func (r *repositoryService) unremove(ctx context.Context, projectName, repoName string) (*Repository,
	*http.Response, error) {
	u := fmt.Sprintf("%vprojects/%v/repos/%v", defaultPathPrefix, projectName, repoName)

	req, err := r.client.newRequest(http.MethodPatch, u, `[{"op":"replace", "path":"/status", "value":"active"}]`)
	if err != nil {
		return nil, nil, err
	}

	repo := new(Repository)
	res, err := r.client.do(ctx, req, repo)
	if err != nil {
		return nil, res, err
	}
	return repo, res, nil
}

func (r *repositoryService) list(ctx context.Context, projectName string) ([]*Repository,
	*http.Response, error) {
	u := fmt.Sprintf("%vprojects/%v/repos", defaultPathPrefix, projectName)

	req, err := r.client.newRequest(http.MethodGet, u, nil)
	if err != nil {
		return nil, nil, err
	}

	var repos []*Repository
	res, err := r.client.do(ctx, req, &repos)
	if err != nil {
		return nil, res, err
	}
	return repos, res, nil
}

func (r *repositoryService) listRemoved(ctx context.Context, projectName string) ([]*Repository,
	*http.Response, error) {
	u := fmt.Sprintf("%vprojects/%v/repos?status=removed", defaultPathPrefix, projectName)

	req, err := r.client.newRequest(http.MethodGet, u, nil)
	if err != nil {
		return nil, nil, err
	}

	var repos []*Repository
	res, err := r.client.do(ctx, req, &repos)
	if err != nil {
		return nil, res, err
	}
	return repos, res, nil
}

func (r *repositoryService) normalizeRevision(
	ctx context.Context, projectName, repoName, revision string) (int, *http.Response, error) {
	u := fmt.Sprintf("%vprojects/%v/repos/%v/revision/%v", defaultPathPrefix, projectName, repoName, revision)

	req, err := r.client.newRequest(http.MethodGet, u, nil)
	if err != nil {
		return -1, nil, err
	}

	rev := new(rev)
	res, err := r.client.do(ctx, req, rev)
	if err != nil {
		return -1, nil, err
	}
	return rev.Rev, res, nil
}

type rev struct {
	Rev int `json:"revision"`
}
