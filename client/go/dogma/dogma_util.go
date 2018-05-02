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
	"math"
	"math/rand"
	"time"
)

func nextDelay(numAttemptsSoFar int) time.Duration {
	var nextDelay time.Duration
	if numAttemptsSoFar == 1 {
		nextDelay = minInterval
	} else {
		calculatedDelay := saturatedMultiply(minInterval, math.Pow(2.0, float64(numAttemptsSoFar-1)))
		if calculatedDelay > maxInterval {
			nextDelay = maxInterval
		} else {
			nextDelay = calculatedDelay
		}
	}
	minJitter := int64(float64(nextDelay) * (1 - jitterRate))
	maxJitter := int64(float64(nextDelay) * (1 + jitterRate))
	bound := maxJitter - minJitter + 1
	random := random(bound)
	result := saturatedAdd(minJitter, random)
	if result < 0 {
		return 0
	} else {
		return time.Duration(result)
	}
}

func saturatedMultiply(left time.Duration, right float64) time.Duration {
	result := float64(left) * right

	if result > float64(maxInt63) {
		return time.Duration(maxInt63)
	} else {
		return time.Duration(result)
	}
}

func random(bound int64) int64 {
	mask := bound - 1
	result := rand.Int63()

	if bound&mask == 0 {
		// power of two
		result &= mask
	} else { // reject over-represented candidates
		var u int64
		u = result >> 1
		for ; u+mask-result < 0; u = rand.Int63() >> 1 {
			result = u % bound
		}
	}
	return result
}

// This code is from Guava library.
func saturatedAdd(a, b int64) int64 {
	naiveSum := a + b
	if a^b < 0 || a^naiveSum >= 0 {
		//If a and b have different signs or a has the same sign as the result then there was no overflow.
		return naiveSum
	}
	return maxInt63 + ((naiveSum >> (64 - 1)) ^ 1)
}
