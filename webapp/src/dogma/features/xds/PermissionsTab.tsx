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
import { Deferred } from 'dogma/common/components/Deferred';
import {
  useAddAppIdentityRepositoryRoleMutation,
  useAddUserRepositoryRoleMutation,
  useDeleteAppIdentityRepositoryRoleMutation,
  useDeleteUserRepositoryRoleMutation,
  useGetAppIdentitiesQuery,
  useGetMetadataQuery,
} from 'dogma/features/xds/xdsApiSlice';
import { RepositoryRole } from 'dogma/features/xds/MetadataDto';
import { RoleEntry, RoleSection } from 'dogma/features/xds/RoleSection';
import { useAppDispatch } from 'dogma/hooks';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';

function toEntries(roles: { [id: string]: RepositoryRole } | undefined): RoleEntry[] {
  if (!roles) {
    return [];
  }
  return Object.entries(roles).map(([id, role]) => ({ id, role }));
}

export const PermissionsTab = ({ group }: { group: string }) => {
  const dispatch = useAppDispatch();
  const { data, isLoading, error } = useGetMetadataQuery(undefined, { refetchOnMountOrArgChange: true });

  const [addUser, { isLoading: isAddingUser }] = useAddUserRepositoryRoleMutation();
  const [deleteUser, { isLoading: isDeletingUser }] = useDeleteUserRepositoryRoleMutation();
  const [addAppId, { isLoading: isAddingAppId }] = useAddAppIdentityRepositoryRoleMutation();
  const [deleteAppId, { isLoading: isDeletingAppId }] = useDeleteAppIdentityRepositoryRoleMutation();

  const { data: allAppIdentities } = useGetAppIdentitiesQuery();

  const repo = data?.repos?.[group];
  const userEntries = toEntries(repo?.roles?.users);
  const appIdEntries = toEntries(repo?.roles?.appIds);

  // App IDs are chosen from the registered application identities, excluding those already granted access.
  // Derive the options only once the query has resolved; while it is loading or has failed, leave them
  // undefined so the add form does not present an in-flight fetch as "no application identities available".
  const grantedAppIds = new Set(appIdEntries.map((e) => e.id));
  const appIdOptions = allAppIdentities
    ? allAppIdentities
        .filter((identity) => !grantedAppIds.has(identity.appId))
        .map((identity) => ({ value: identity.appId, label: identity.appId }))
    : undefined;

  const addUserRole = async (id: string, role: RepositoryRole): Promise<boolean> => {
    try {
      await addUser({ group, data: { id, role } }).unwrap();
      dispatch(newNotification('User added', `'${id}' now has ${role} access`, 'success'));
      return true;
    } catch (err) {
      dispatch(newNotification('Failed to add the user', ErrorMessageParser.parse(err), 'error'));
      return false;
    }
  };
  const deleteUserRole = async (id: string) => {
    try {
      await deleteUser({ group, id }).unwrap();
      dispatch(newNotification('User removed', `'${id}' no longer has access`, 'success'));
    } catch (err) {
      dispatch(newNotification('Failed to remove the user', ErrorMessageParser.parse(err), 'error'));
    }
  };
  const addAppIdRole = async (id: string, role: RepositoryRole): Promise<boolean> => {
    try {
      await addAppId({ group, data: { id, role } }).unwrap();
      dispatch(newNotification('App ID added', `'${id}' now has ${role} access`, 'success'));
      return true;
    } catch (err) {
      dispatch(newNotification('Failed to add the app ID', ErrorMessageParser.parse(err), 'error'));
      return false;
    }
  };
  const deleteAppIdRole = async (id: string) => {
    try {
      await deleteAppId({ group, id }).unwrap();
      dispatch(newNotification('App ID removed', `'${id}' no longer has access`, 'success'));
    } catch (err) {
      dispatch(newNotification('Failed to remove the app ID', ErrorMessageParser.parse(err), 'error'));
    }
  };

  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
        <>
          <RoleSection
            title="Users"
            entityLabel="User"
            group={group}
            entries={userEntries}
            isAdding={isAddingUser}
            isDeleting={isDeletingUser}
            onAdd={addUserRole}
            onDelete={deleteUserRole}
          />
          <RoleSection
            title="Application IDs"
            entityLabel="App ID"
            group={group}
            entries={appIdEntries}
            isAdding={isAddingAppId}
            isDeleting={isDeletingAppId}
            onAdd={addAppIdRole}
            onDelete={deleteAppIdRole}
            options={appIdOptions}
          />
        </>
      )}
    </Deferred>
  );
};
