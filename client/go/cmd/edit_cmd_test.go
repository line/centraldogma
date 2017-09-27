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

func TestNewEditCommand(t *testing.T) {
	defaultRemoteURI, _ := url.Parse("http://localhost:36462/api/v0/")

	var tests = []struct {
		arguments []string
		revision  string
		want      interface{}
	}{
		{[]string{"foo/bar/a.txt"}, "",
			editFileCommand{repo: repositoryRequestInfo{
				remote: defaultRemoteURI, projectName: "foo", repositoryName: "bar",
				repositoryPath: "/a.txt", revision: "head"}}},

		{[]string{"foo/bar/b/a.txt"}, "10",
			editFileCommand{repo: repositoryRequestInfo{
				remote: defaultRemoteURI, projectName: "foo", repositoryName: "bar",
				repositoryPath: "/b/a.txt", revision: "10"}}},
	}

	for _, test := range tests {
		flags := flag.FlagSet{}
		flags.Parse(test.arguments)
		parent := cli.NewContext(nil, &flag.FlagSet{}, nil)
		c := cli.NewContext(nil, &flags, parent)
		got, _ := newEditCommand(c, test.revision)
		switch comType := got.(type) {
		case *editFileCommand:
			got2 := editFileCommand(*comType)
			if !reflect.DeepEqual(got2, test.want) {
				t.Errorf("newEditCommand(%q) = %q, want: %q", test.arguments, got2, test.want)
			}
		default:
			t.Errorf("newEditCommand(%q) = %q, want: %q", test.arguments, got, test.want)
		}
	}
}
