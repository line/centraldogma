/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

export interface ReplicaInfo {
  serverId: number;
  host: string;
  // Whether this replica served the request.
  current: boolean;
}

export interface RecoverRepositoryResponse {
  // COMPLETED when the request landed on the source replica and the recovery already ran;
  // REQUESTED when the source replica was asked to originate it asynchronously.
  status: 'COMPLETED' | 'REQUESTED';
  headRevision?: number;
}
