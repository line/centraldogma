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
import { useGetProjectsQuery } from 'dogma/features/api/apiSlice';
import { Deferred } from 'dogma/common/components/Deferred';
import { Heading, Link, Table, TableCaption, TableContainer, Tbody, Td, Tr } from '@chakra-ui/react';
import { default as RoutingLink } from 'next/link';
import { SettingsIcon } from '@chakra-ui/icons';

// TODO(ikhoon):
//   - Add more information to the projects table.
//   - Add a filter to easily find a project from the table.
//   - Paginate projects?
export const Projects = () => {
  const { data: projects, error, isLoading } = useGetProjectsQuery();
  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => {
        return (
          <div>
            <Heading pb={6}>Projects</Heading>
            <TableContainer>
              <Table variant="simple">
                <TableCaption>Projects</TableCaption>
                <Tbody>
                  {projects.map((project) => (
                    <Tr key={project.name}>
                      <Td>
                        <Link as={RoutingLink} href={`/app/projects/${project.name}`} fontSize="md">
                          {project.name}
                        </Link>
                      </Td>
                      <Td>
                        <SettingsIcon />
                      </Td>
                    </Tr>
                  ))}
                </Tbody>
              </Table>
            </TableContainer>
          </div>
        );
      }}
    </Deferred>
  );
};
