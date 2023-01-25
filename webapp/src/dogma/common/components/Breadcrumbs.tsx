import { Breadcrumb, BreadcrumbItem, BreadcrumbLink, Text } from '@chakra-ui/react';
import { FcNext } from 'react-icons/fc';
import NextLink from 'next/link';

export const Breadcrumbs = ({
  path,
  omitIndexList,
  // /project/projectName/repos/repoName -> /project/projectName/repos/repoName/list/head
  suffixes,
}: {
  path: string;
  omitIndexList: number[];
  suffixes: { [key: number]: string };
}) => {
  const asPathNestedRoutes = path
    // If the path belongs to a file, the top level should be a directory
    .replace('/files/head', '/list/head')
    .split('/')
    .filter((v) => v.length > 0);
  if (asPathNestedRoutes && asPathNestedRoutes[asPathNestedRoutes.length - 1].startsWith('#')) {
    asPathNestedRoutes.pop();
  }
  const prefixes: string[] = [];
  return (
    <Breadcrumb spacing="8px" separator={<FcNext />} mb={5} fontWeight="medium" fontSize="sm">
      {asPathNestedRoutes.map((page, i) => {
        prefixes.push(page);
        if (!omitIndexList.includes(i)) {
          return (
            <BreadcrumbItem key={i}>
              {i < asPathNestedRoutes.length - 1 ? (
                <BreadcrumbLink as={NextLink} href={`/${prefixes.join('/')}${suffixes[i] || ''}`}>
                  {decodeURI(page)}
                </BreadcrumbLink>
              ) : (
                <Text> {decodeURI(page)}</Text>
              )}
            </BreadcrumbItem>
          );
        }
      })}
    </Breadcrumb>
  );
};
