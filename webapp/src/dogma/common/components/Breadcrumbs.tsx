import { Breadcrumb, BreadcrumbItem, BreadcrumbLink, Text } from '@chakra-ui/react';
import { FcNext } from 'react-icons/fc';
import NextLink from 'next/link';

interface BreadcrumbsProps {
  path: string;
  omitIndexList?: number[];
  omitQueryList?: number[];
  unlinkedList?: number[];
  replaces?: { [key: number]: string };
  suffixes?: { [key: number]: string };
  query?: string;
}

export const Breadcrumbs = ({
  path,
  omitIndexList = [],
  omitQueryList = [],
  unlinkedList = [],
  replaces = {},
  // /project/projectName/repos/repoName -> /project/projectName/repos/repoName/tree/head
  suffixes = {},
  query = '',
}: BreadcrumbsProps) => {
  const asPathNestedRoutes = path
    // If the path belongs to a file, the top level should be a directory
    .replace('/files/head', '/tree/head')
    .split('/')
    .filter((v) => v.length > 0);
  if (asPathNestedRoutes && asPathNestedRoutes[asPathNestedRoutes.length - 1].startsWith('#')) {
    asPathNestedRoutes.pop();
  }
  const prefixes: string[] = [];
  return (
    <Breadcrumb spacing="8px" separator={<FcNext />} mb={8} fontWeight="medium" fontSize="2xl">
      {asPathNestedRoutes.map((page, i) => {
        const item = replaces[i] || page;
        prefixes.push(item);
        if (omitIndexList.includes(i)) {
          return null;
        }
        let query0 = '';
        if (!omitQueryList.includes(i)) {
          query0 = query ? `?${query}` : '';
        }

        return (
          <BreadcrumbItem key={i}>
            {!unlinkedList.includes(i) && i < asPathNestedRoutes.length - 1 ? (
              <BreadcrumbLink
                as={NextLink}
                href={`/${prefixes.join('/')}${suffixes[i] || ''}${query0}`}
                paddingBottom={1}
              >
                {decodeURI(item)}
              </BreadcrumbLink>
            ) : (
              <Text paddingBottom={1}> {decodeURI(item)}</Text>
            )}
          </BreadcrumbItem>
        );
      })}
    </Breadcrumb>
  );
};
