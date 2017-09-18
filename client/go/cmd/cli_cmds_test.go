// Copyright 2017 LINE Corporation
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

package cmd

import (
	"net/url"
	"reflect"
	"testing"

	"github.com/line/centraldogma/client/go/service"
)

func TestGetServerURI(t *testing.T) {
	var tests = []struct {
		host string
		want *url.URL
	}{
		// no input
		{"", &url.URL{
			Scheme: "http",
			Host:   "localhost:36462",
			Path:   service.DefaultPathPrefix,
		}},
		{"http://foo.com", &url.URL{
			Scheme: "http",
			Host:   "foo.com:36462",
			Path:   service.DefaultPathPrefix,
		}},
		{"http://foo.com:8080", &url.URL{
			Scheme: "http",
			Host:   "foo.com:8080",
			Path:   service.DefaultPathPrefix,
		}},
		{"https://foo.com:8080/", &url.URL{
			Scheme: "https",
			Host:   "foo.com:8080",
			Path:   service.DefaultPathPrefix,
		}},
		{"foo.com", &url.URL{
			Scheme: "http",
			Host:   "foo.com:36462",
			Path:   service.DefaultPathPrefix,
		}},
		{"foo.com:8080", &url.URL{
			Scheme: "http",
			Host:   "foo.com:8080",
			Path:   service.DefaultPathPrefix,
		}},
		{"http://192.168.0.1", &url.URL{
			Scheme: "http",
			Host:   "192.168.0.1:36462",
			Path:   service.DefaultPathPrefix,
		}},
		{"http://192.168.0.1:8080", &url.URL{
			Scheme: "http",
			Host:   "192.168.0.1:8080",
			Path:   service.DefaultPathPrefix,
		}},
	}

	for _, test := range tests {
		if got, _ := getRemoteURI(test.host); !reflect.DeepEqual(got, test.want) {
			t.Errorf("getRemoteURI(%q) = %q, want: %q", test.host, got, test.want)
		}
	}
}
