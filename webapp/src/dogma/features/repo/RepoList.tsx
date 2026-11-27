import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { RepoDto } from 'dogma/features/repo/RepoDto';
import { isPublicRepo } from 'dogma/features/repo/RepositoriesMetadataDto';
import { ProjectMetadataDto } from 'dogma/features/project/ProjectMetadataDto';
import { findUserRepositoryRole } from 'dogma/features/auth/RepositoryRole';
import { useAppSelector } from 'dogma/hooks';
import { useMemo } from 'react';
import { Author } from 'dogma/common/components/Author';
import { RepoIcon } from 'dogma/common/components/RepoIcon';

export type RepoListProps<Data extends object> = {
  data: Data[];
  projectName: string;
  metadata?: ProjectMetadataDto;
};

const RepoList = <Data extends object>({ data, projectName, metadata }: RepoListProps<Data>) => {
  const { user, isInAnonymousMode } = useAppSelector((state) => state.auth);
  const columnHelper = createColumnHelper<RepoDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: RepoDto) => row.name, {
        cell: (info) => {
          const repoName = info.getValue();
          const isAccessible =
            isInAnonymousMode ||
            !metadata ||
            !user ||
            findUserRepositoryRole(repoName, user, metadata) !== null;
          return (
            <RepoIcon
              projectName={projectName}
              repoName={repoName}
              isActive={true}
              isPublic={isPublicRepo(metadata?.repos?.[repoName])}
              isAccessible={isAccessible}
            />
          );
        },
        header: 'Name',
      }),
      columnHelper.accessor((row: RepoDto) => row.creator.name, {
        cell: (info) => <Author name={info.getValue()} />,
        header: 'Creator',
      }),
      columnHelper.accessor((row: RepoDto) => row.createdAt, {
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
        header: 'Created',
      }),
      columnHelper.accessor((row: RepoDto) => row.headRevision, {
        // TODO: Show the commit message of HEAD revision
        cell: (info) => info.getValue(),
        header: 'HEAD',
        meta: {
          isNumeric: true,
        },
      }),
    ],
    [columnHelper, projectName, metadata, user, isInAnonymousMode],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<Data>[]} data={data} />;
};

export default RepoList;
