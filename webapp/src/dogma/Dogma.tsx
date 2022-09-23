/*
 * Copyright 2022 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

import React from 'react';
import { HistoryRouter as Router } from 'redux-first-history/rr6';
import { history } from 'dogma/store';
import { Route, Routes } from 'react-router-dom';
import { LoginForm } from 'dogma/features/auth/LoginForm';
import { Authorized } from 'dogma/features/auth/Authorized';
import { Projects } from 'dogma/features/project/Projects';

export default () => (
  <Router history={history}>
    <Routes>
      <Route path="/web/auth/login" element={<LoginForm />} />
      <Route element={<Authorized />}>
        {/* TODO(ikhoon): Add a landing page */}
        <Route path="/" element={<Projects />} />
        <Route path="/app/projects" element={<Projects />} />
      </Route>
    </Routes>
  </Router>
);
