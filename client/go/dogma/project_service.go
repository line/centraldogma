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
	"net/http"
)

type projectService service

// Project represents a project in the Central Dogma server.
type Project struct {
	Name      string  `json:"name"`
	Creator   *Author `json:"creator,omitempty"`
	URL       string  `json:"url,omitempty"`
	CreatedAt string  `json:"createdAt,omitempty"`
}

type Author struct {
	Name  string `json:"name,omitempty"`
	Email string `json:"email,omitempty"`
}

func (p *projectService) create(ctx context.Context, name string) (*Project, *http.Response, error) {
	u := defaultPathPrefix + "projects"

	req, err := p.client.newRequest("POST", u, &Project{Name: name})
	if err != nil {
		return nil, nil, err
	}

	project := new(Project)
	res, err := p.client.do(ctx, req, project)
	if err != nil {
		return nil, res, err
	}
	return project, res, nil
}

func (p *projectService) remove(ctx context.Context, name string) (*http.Response, error) {
	u := defaultPathPrefix + "projects/" + name

	req, err := p.client.newRequest("DELETE", u, nil)
	if err != nil {
		return nil, err
	}

	res, err := p.client.do(ctx, req, nil)
	if err != nil {
		return res, err
	}
	return res, nil
}

func (p *projectService) unremove(ctx context.Context, name string) (*Project, *http.Response, error) {
	u := defaultPathPrefix + "projects/" + name

	req, err := p.client.newRequest("PATCH", u, `[{"op":"replace", "path":"/status", "value":"active"}]`)
	if err != nil {
		return nil, nil, err
	}

	project := new(Project)
	res, err := p.client.do(ctx, req, project)
	if err != nil {
		return nil, res, err
	}
	return project, res, nil
}

func (p *projectService) list(ctx context.Context) ([]*Project, *http.Response, error) {
	u := defaultPathPrefix + "projects"

	req, err := p.client.newRequest("GET", u, nil)
	if err != nil {
		return nil, nil, err
	}

	var projects []*Project
	res, err := p.client.do(ctx, req, &projects)

	if err != nil {
		return nil, res, err
	}
	return projects, res, nil
}

func (p *projectService) listRemoved(ctx context.Context) ([]*Project, *http.Response, error) {
	u := defaultPathPrefix + "projects?status=removed"

	req, err := p.client.newRequest("GET", u, nil)
	if err != nil {
		return nil, nil, err
	}

	var projects []*Project
	res, err := p.client.do(ctx, req, &projects)
	if err != nil {
		return nil, res, err
	}
	return projects, res, nil
}
