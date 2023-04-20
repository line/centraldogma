import { Breadcrumb, BreadcrumbItem, BreadcrumbLink, Text } from '@chakra-ui/react';
import { FcNext } from 'react-icons/fc';
import NextLink from 'next/link';

interface BreadcrumbsProps {
  path: string;
  omitIndexList?: number[];
  replaces?: { [key: number]: string };
  suffixes?: { [key: number]: string };
}

export const Breadcrumbs = ({
  path,
  omitIndexList = [],
  replaces = {},
  // /project/projectName/repos/repoName -> /project/projectName/repos/repoName/list/head
  suffixes = {},
}: BreadcrumbsProps) => {
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
    <Breadcrumb spacing="8px" separator={<FcNext />} mb={8} fontWeight="medium" fontSize="lg">
      {asPathNestedRoutes.map((page, i) => {
        prefixes.push(page);
        const item = replaces[i] || page;
        if (!omitIndexList.includes(i)) {
          return (
            <BreadcrumbItem key={i}>
              {i < asPathNestedRoutes.length - 1 ? (
                <BreadcrumbLink as={NextLink} href={`/${prefixes.join('/')}${suffixes[i] || ''}`}>
                  {decodeURI(item)}
                </BreadcrumbLink>
              ) : (
                <Text> {decodeURI(item)}</Text>
              )}
            </BreadcrumbItem>
          );
        }
      })}
    </Breadcrumb>
  );
};
