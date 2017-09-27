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

package client

import (
	"net/http"
	"time"

	"github.com/urfave/cli"
)

type CDClient struct {
	client *http.Client
}

func (cdc *CDClient) Do(req *http.Request, c *cli.Context) (*http.Response, error) {
	tokenOrSessionID, err := loginIfNeed(req.URL, c.Parent().String("token"), c.Parent().String("login"))
	if err != nil {
		return nil, err
	}
	if req.Header == nil {
		req.Header = http.Header{}
	}
	req.Header.Add("Authorization", "Bearer "+tokenOrSessionID)
	return cdc.client.Do(req)
}

func New() *CDClient {
	return &CDClient{
		client: &http.Client{
			Timeout: time.Second * 10,
		},
	}
}
