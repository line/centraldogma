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
	"encoding/json"
	"fmt"
	"net/http"
	"reflect"
	"testing"
)

func TestCreateProject(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	input := &Project{Name: "foo"}

	mux.HandleFunc("/api/v1/projects", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "POST")
		testHeader(t, r, "Authorization", "Bearer anonymous")

		project := new(Project)
		json.NewDecoder(r.Body).Decode(project)
		if !reflect.DeepEqual(project, input) {
			t.Errorf("Request body = %+v, want %+v", project, input)
		}
		w.WriteHeader(http.StatusCreated)
		w.Header().Set("Location", "/api/v1/projects/foo")
		fmt.Fprint(w, `{"name":"foo", "creator":"minux@m.x"}`)
	})

	project, res, _ := c.CreateProject(context.Background(), input.Name)
	testStatus(t, res, 201)

	want := &Project{Name: "foo", Creator: "minux@m.x"}
	if !reflect.DeepEqual(project, want) {
		t.Errorf("CreateProject returned %+v, want %+v", project, want)
	}
}

func TestRemoveProject(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects/foo", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "DELETE")
		w.WriteHeader(http.StatusNoContent)
	})

	res, _ := c.RemoveProject(context.Background(), "foo")
	testStatus(t, res, 204)
}

func TestUnremoveProject(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects/foo", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "PATCH")
		testHeader(t, r, "Content-Type", "application/json-patch+json")
		testBody(t, r, `[{"op":"replace", "path":"/status", "value":"active"}]`)
		fmt.Fprint(w, `{"name":"foo", "creator":"minux@m.x", "url":"/api/v1/projects/foo"}`)
	})

	project, _, _ := c.UnremoveProject(context.Background(), "foo")

	want := &Project{Name: "foo", Creator: "minux@m.x", URL: "/api/v1/projects/foo"}
	if !reflect.DeepEqual(project, want) {
		t.Errorf("UnremoveProject returned %+v, want %+v", project, want)
	}
}

func TestListProject(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "GET")
		fmt.Fprint(w, `[{"name":"foo", "creator":"minux@m.x", "url":"/api/v1/projects/foo"},
{"name":"bar", "creator":"minux@m.x", "url":"/api/v1/projects/bar"}]`)
	})

	projects, _, _ := c.ListProjects(context.Background())
	want := []*Project{{Name: "foo", Creator: "minux@m.x", URL: "/api/v1/projects/foo"},
		{Name: "bar", Creator: "minux@m.x", URL: "/api/v1/projects/bar"}}
	if !reflect.DeepEqual(projects, want) {
		t.Errorf("ListProjects returned %+v, want %+v", projects, want)
	}
}

func TestListRemovedProject(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/api/v1/projects", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "GET")
		testQuery(t, r, "status", "removed")
		fmt.Fprint(w, `[{"name":"foo"}, {"name":"bar"}]`)
	})

	projects, _, _ := c.ListRemovedProjects(context.Background())
	want := []*Project{{Name: "foo"}, {Name: "bar"}}
	if !reflect.DeepEqual(projects, want) {
		t.Errorf("ListRemovedProjects returned %+v, want %+v", projects, want)
	}
}
