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
	"io/ioutil"
	"net/http"
	"net/http/httptest"
	"testing"
)

// setup sets up a test HTTP server along with a github.Client that is
// configured to talk to that test server. Tests should register handlers on
// mux which provide mock responses for the API method being tested.
func setup() (c *Client, mux *http.ServeMux, teardown func()) {
	mux = http.NewServeMux()
	server := httptest.NewServer(mux)

	c, _ = NewClientWithHTTPClient(server.URL, http.DefaultClient)
	return c, mux, server.Close
}

func testMethod(t *testing.T, req *http.Request, want string) {
	if got := req.Method; got != want {
		t.Errorf("Request method: %v, want %v", got, want)
	}
}

func testHeader(t *testing.T, req *http.Request, header string, want string) {
	if got := req.Header.Get(header); got != want {
		t.Errorf("Header.Get(%q) returned %q, want %q", header, got, want)
	}
}

func testURLQuery(t *testing.T, r *http.Request, key, want string) {
	if got := r.URL.Query().Get(key); got != want {
		t.Errorf("Query.Get(%q) returned %q, want %q", key, got, want)
	}
}

func testBody(t *testing.T, req *http.Request, want string) {
	b, err := ioutil.ReadAll(req.Body)
	if err != nil {
		t.Errorf("Error reading request body: %v", err)
	}
	if got := string(b); got != want {
		t.Errorf("Request body: %v, want %v", got, want)
	}
}

func testStatus(t *testing.T, res *http.Response, want int) {
	if got := res.StatusCode; got != want {
		t.Errorf("Response status: %v, want %v", got, want)
	}
}

func testString(t *testing.T, got, want, name string) {
	if got != want {
		t.Errorf("%v: %v, want %v", name, got, want)
	}
}

func TestNewClient(t *testing.T) {
	mux := http.NewServeMux()
	server := httptest.NewServer(mux)
	defer server.Close()

	c, _ := NewClient(server.URL, "foo", "bar")

	mux.HandleFunc("/api/v0/authenticate", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "POST")
		r.ParseForm()
		testString(t, r.PostForm.Get("grant_type"), "client_credentials", "grant_type")

		username, password, _ := r.BasicAuth()
		testString(t, username, "foo", "username")
		testString(t, password, "bar", "password")

		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"access_token":"fdbe78b0-1d0c-4978-bbb1-9bc7106dad36","token_type":"Bearer"}`)
	})

	mux.HandleFunc("/test", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "GET")
		testHeader(t, r, "Authorization", "Bearer fdbe78b0-1d0c-4978-bbb1-9bc7106dad36")
	})

	req, _ := c.newRequest("GET", "/test", nil)
	// the request goes to the pathAuthenticate first to get the token, then goes to "/test"
	res, _ := c.do(context.Background(), req, nil)
	testStatus(t, res, 200)
}

func TestNewClientWithHTTPClient(t *testing.T) {
	var tests = []struct {
		baseURL string
		want    string
	}{
		{"", defaultBaseURL},
		{"central-dogma.com", "http://central-dogma.com:36462/"},
		{"central-dogma.com:80", "http://central-dogma.com:80/"},
		{"https://central-dogma.com", "https://central-dogma.com:36462/"},
	}

	for _, test := range tests {
		if got, _ := NewClientWithHTTPClient(test.baseURL, http.DefaultClient); got.baseURL.String() != test.want {
			t.Errorf("NewClientWithHTTPClient BaseURL is %v, want %v", got, test.want)
		}
	}
}
