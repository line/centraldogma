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
import Head from 'next/head';
import { useRouter } from 'next/router';
import { useGetTitleQuery } from 'dogma/features/api/apiSlice';
import { getPageTitleParts, toSiteTitle } from 'dogma/util/page-title';

export const PageTitle = () => {
  const router = useRouter();
  const { data: titleDto } = useGetTitleQuery();
  const parts = getPageTitleParts(router.pathname, router.query);
  const siteTitle = toSiteTitle(titleDto);
  const title = parts.length > 0 ? `${parts.join(' · ')} | ${siteTitle}` : siteTitle;
  return (
    <Head>
      <title>{title}</title>
    </Head>
  );
};
