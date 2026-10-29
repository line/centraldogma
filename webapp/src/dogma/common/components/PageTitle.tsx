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
import { useGetTitleQuery } from 'dogma/features/api/apiSlice';

export const useSiteTitle = () => {
  const { data } = useGetTitleQuery();
  return data?.title.replace('{{hostname}}', data.hostname) || 'Central Dogma';
};

// `title` lists the parts of the page title, most specific first.
export const PageTitle = ({ title = [] }: { title?: string | (string | undefined)[] }) => {
  const siteTitle = useSiteTitle();
  const parts = [title].flat().filter(Boolean);
  return (
    <Head>
      <title>{parts.length > 0 ? `${siteTitle} | ${parts.join(' · ')}` : siteTitle}</title>
    </Head>
  );
};
