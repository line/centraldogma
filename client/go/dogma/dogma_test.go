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

	c, _ = NewClient(server.URL)
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

func testQuery(t *testing.T, r *http.Request, key, want string) {
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

func TestNewClient(t *testing.T) {
	var tests = []struct {
		baseURL string
		want    string
	}{
		{"", DefaultBaseURL},
		{"central-dogma", "http://central-dogma:36462/"},
		{"central-dogma:80", "http://central-dogma:80/"},
		{"https://central-dogma", "https://central-dogma:36462/"},
	}

	for _, test := range tests {
		if got, _ := NewClient(test.baseURL); got.BaseURL.String() != test.want {
			t.Errorf("NewClient BaseURL is %v, want %v", got, test.want)
		}
	}
}

func TestNewClientWithSessionID(t *testing.T) {
	c, _ := NewClientWithSessionID("", "fdbe78b0-1d0c-4978-bbb1-9bc7106dad36")

	if got, want := c.BaseURL.String(), DefaultBaseURL; got != want {
		t.Errorf("NewClient BaseURL is %v, want %v", got, want)
	}
	if got, want := c.sessionID, "fdbe78b0-1d0c-4978-bbb1-9bc7106dad36"; got != want {
		t.Errorf("NewClient sessionID is %v, want %v", got, want)
	}
}

func TestLogin(t *testing.T) {
	c, mux, teardown := setup()
	defer teardown()

	mux.HandleFunc("/security_enabled", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "GET")
		fmt.Fprint(w, true)
	})
	mux.HandleFunc("/api/v0/authenticate", func(w http.ResponseWriter, r *http.Request) {
		testMethod(t, r, "POST")
		fmt.Fprint(w, "fdbe78b0-1d0c-4978-bbb1-9bc7106dad36")
	})

	if got, want := c.sessionID, "anonymous"; got != want {
		t.Errorf("client sessionID is %v, want %v", got, want)
	}
	c.Login("foo", "bar")

	if got, want := c.sessionID, "fdbe78b0-1d0c-4978-bbb1-9bc7106dad36"; got != want {
		t.Errorf("client sessionID is %v, want %v", got, want)
	}
}
