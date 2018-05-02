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
	"math/rand"
	"testing"
	"time"
)

func TestNextDelay(t *testing.T) {
	rand.Seed(0)

	delay := nextDelay(0)
	testDelay(t, delay, "1.073713114s")
	delay = nextDelay(1)
	testDelay(t, delay, "1.920167497s")
	delay = nextDelay(2)
	testDelay(t, delay, "4.320190114s")
	delay = nextDelay(3)
	testDelay(t, delay, "6.966156812s")
	delay = nextDelay(4)
	testDelay(t, delay, "16.842968555s")
}

func testDelay(t *testing.T, delay time.Duration, want string) {
	if delay.String() != want {
		t.Errorf("delay: %v, want %v", delay, want)
	}
}
