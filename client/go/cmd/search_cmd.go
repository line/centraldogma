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
	"fmt"
	"net/http"
	"net/url"

	"github.com/line/centraldogma/client/go/client"
	"github.com/line/centraldogma/client/go/json"
	"github.com/urfave/cli"
)

type searchCommand struct {
	repo  repositoryRequestInfo
	term  string
	style PrintStyle
}

func (sc *searchCommand) execute(c *cli.Context) error {
	repo := sc.repo
	u, _ := url.Parse(
		repo.remote.String() + "projects/" + repo.projectName + "/repositories/" + repo.repositoryName +
			"/search/revisions/" + repo.revision)
	values := url.Values{}
	values.Set("term", sc.term)
	u.RawQuery = values.Encode()
	req := &http.Request{Method: http.MethodGet, URL: u}

	res, err := client.New().Do(req, c)
	if err != nil {
		return err
	}

	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("cannot find files matched by %q in /%s/%s with revision: %q (status: %s)",
			sc.term, repo.projectName, repo.repositoryName, repo.revision, res.Status)
	}
	entries := []json.Entry{}
	if err = json.Fill(&entries, res.Body); err != nil {
		return err
	}
	printWithStyle(entries, sc.style)
	return nil
}

func newSearchCommand(c *cli.Context, revision string, style PrintStyle) (Command, error) {
	ca := c.Args()
	if len(ca) == 0 || len(ca) > 2 {
		return nil, newCommandLineError(c)
	}

	repo, err := newRepositoryRequestInfo(c, revision, false)
	if err != nil {
		return nil, err
	}
	term := "/"
	if len(ca) != 0 {
		term = ca.Get(1)
	}

	return &searchCommand{repo: repo, term: term, style: style}, nil
}
