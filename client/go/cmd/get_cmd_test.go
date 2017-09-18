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
	"flag"
	"net/url"
	"reflect"
	"testing"

	"github.com/urfave/cli"
)

func TestNewGetCommand(t *testing.T) {
	defaultRemoteURI, _ := url.Parse("http://localhost:36462/api/v0/")

	var tests = []struct {
		arguments []string
		revision  string
		want      interface{}
	}{
		{[]string{"foo/bar/a.txt"}, "",
			getFileCommand{repo: repositoryRequestInfo{
				remote: defaultRemoteURI, projectName: "foo", repositoryName: "bar",
				repositoryPath: "/a.txt", revision: "head"},
				localFilePath: "a.txt"}},

		{[]string{"foo/bar/b/a.txt"}, "10",
			getFileCommand{repo: repositoryRequestInfo{
				remote: defaultRemoteURI, projectName: "foo", repositoryName: "bar",
				repositoryPath: "/b/a.txt", revision: "10"},
				localFilePath: "a.txt"}},

		{[]string{"foo/bar/b/a.txt", "c.txt"}, "",
			getFileCommand{repo: repositoryRequestInfo{
				remote: defaultRemoteURI, projectName: "foo", repositoryName: "bar",
				repositoryPath: "/b/a.txt", revision: "head"},
				localFilePath: "c.txt"}},

		{[]string{"foo/bar/a.txt", "b/c.txt"}, "",
			getFileCommand{repo: repositoryRequestInfo{
				remote: defaultRemoteURI, projectName: "foo", repositoryName: "bar",
				repositoryPath: "/a.txt", revision: "head"},
				localFilePath: "b/c.txt"}},
	}

	for _, test := range tests {
		flags := flag.FlagSet{}
		flags.Parse(test.arguments)
		parent := cli.NewContext(nil, &flag.FlagSet{}, nil)
		c := cli.NewContext(nil, &flags, parent)
		got, _ := newGetCommand(c, test.revision, "")
		switch comType := got.(type) {
		case *getFileCommand:
			got2 := getFileCommand(*comType)
			if !reflect.DeepEqual(got2, test.want) {
				t.Errorf("newGetCommand(%q) = %q, want: %q", test.arguments, got2, test.want)
			}
		default:
			t.Errorf("newGetCommand(%q) = %q, want: %q", test.arguments, got, test.want)
		}
	}
}
