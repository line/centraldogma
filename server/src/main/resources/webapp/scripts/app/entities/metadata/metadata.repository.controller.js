'use strict';

angular.module('CentralDogmaAdmin')
    .controller('MetadataRepositoryController',
                function ($scope, $state, $stateParams, ApiV1Service, $uibModal, Permission, ConfirmationDialog,
                          Principal, CentralDogmaConstant, StringUtil, NotificationUtil, EntitiesUtil) {
                  $scope.project = {
                    name: $stateParams.projectName
                  };
                  $scope.repository = {
                    name: $stateParams.repoName
                  };
                  $scope.sanitizeEmail = EntitiesUtil.sanitizeEmail;

                  // Common functions.
                  $scope.onCheckboxClicked = function(perm) {
                    if (perm.write === true) {
                      perm.read = true;
                    }
                  };

                  function updatePerUserOrPerTokenPermissions(category, id, perm, translateId) {
                    ConfirmationDialog.openModal(translateId, {
                      target: EntitiesUtil.sanitizeEmail(id)
                    }).then(function () {
                      ApiV1Service.jsonPatch(
                        StringUtil.encodeUri(['metadata', $scope.project.name,
                                              'repos', $scope.repository.name,
                                              'perm', category, id]),
                        EntitiesUtil.toReplaceJsonPatch('/permissions', Permission.toSet(perm))
                      ).then(function () {
                        $scope.refresh();
                      }, function (error) {
                        NotificationUtil.error(error);
                      });
                    });
                  }

                  function removePermTable(category, target, translateId) {
                    ConfirmationDialog.openModal(translateId, {
                      target: EntitiesUtil.sanitizeEmail(target)
                    }).then(function () {
                      ApiV1Service.delete(
                        StringUtil.encodeUri(['metadata', $scope.project.name,
                                              'repos', $scope.repository.name,
                                              'perm', category, target])
                      ).then(function () {
                        $scope.refresh();
                      }, function (error) {
                        NotificationUtil.error(error);
                      });
                    });
                  }

                  function savePermission(category, target, list, translateId) {
                    ConfirmationDialog.openModal(translateId, {
                      target: EntitiesUtil.sanitizeEmail(target.name)
                    }).then(function () {
                      ApiV1Service.post(
                        StringUtil.encodeUri(['metadata', $scope.project.name,
                                              'repos', $scope.repository.name,
                                              'perm', category]),
                        Permission.toRequest(target.name, target)
                      ).then(function () {
                        list.remove(target);
                        $scope.refresh();
                      }, function (error) {
                        NotificationUtil.error(error);
                      });
                    });
                  }

                  // Role permission
                  $scope.onPerRolePermissionsTableCheckboxClicked = function(perm) {
                    $scope.onCheckboxClicked(perm);
                    $scope.rolePermTableChanged = !angular.equals($scope.rolePermTable, $scope.originalRolePermTable);
                  };
                  $scope.updatePerRolePermissions = function () {
                    ConfirmationDialog.openModal('entities.title_update_role_permission', {
                      target: $scope.repository.name
                    }).then(function () {
                      var request = {};
                      Object.entries($scope.rolePermTable).forEach(function (entry) {
                        request[entry[0]] = Permission.toSet(entry[1]);
                      });
                      ApiV1Service.post(
                        StringUtil.encodeUri(['metadata', $scope.project.name,
                                              'repos', $scope.repository.name,
                                              'perm', 'role']), request
                      ).then(function () {
                        $scope.refresh();
                      }, function (error) {
                        NotificationUtil.error(error);
                      });
                    });
                  };

                  // User permission
                  $scope.newUserPermission = new Permission();
                  $scope.saveNewPerUserPermissions = function (userWithPermission) {
                    savePermission('users', userWithPermission, $scope.newUserPermission, 'entities.title_save_member');
                  };
                  $scope.isPerUserPermissionsChanged = function (user, perm) {
                    return !angular.equals(perm, $scope.originalUserPermTable[user]);
                  };
                  $scope.updatePerUserPermissions = function (user, perm) {
                    updatePerUserOrPerTokenPermissions('users', user, perm, 'entities.title_update_member');
                  };
                  $scope.removePerUserPermissions = function (user) {
                    removePermTable('users', user, 'entities.title_remove_member');
                  };

                  // Token permission
                  $scope.newTokenPermission = new Permission();
                  $scope.saveNewPerTokenPermissions = function (tokenWithPermission) {
                    savePermission('tokens', tokenWithPermission, $scope.newTokenPermission, 'entities.title_save_token');
                  };
                  $scope.isPerTokenPermissionsChanged = function (appId, perm) {
                    return !angular.equals(perm, $scope.originalTokenPermTable[appId]);
                  };
                  $scope.updatePerTokenPermissions = function (appId, perm) {
                    updatePerUserOrPerTokenPermissions('tokens', appId, perm, 'entities.title_update_token');
                  };
                  $scope.removePerTokenPermissions = function (appId) {
                    removePermTable('tokens', appId, 'entities.title_remove_token');
                  };

                  $scope.refresh = function() {
                    ApiV1Service.get("projects/" + $scope.project.name).then(function (metadata) {
                      var registeredAppIdList, registeredMemberList;

                      $scope.metadata = metadata;
                      $scope.currentRepo = metadata.repos[$scope.repository.name];
                      $scope.role = Principal.projectRole(metadata);
                      $scope.isOwner = function () {
                        return angular.equals($scope.role, CentralDogmaConstant.PROJECT_ROLE_OWNER);
                      };
                      $scope.isNotOwner = function () {
                        return !$scope.isOwner();
                      };
                      $scope.isNotOwnerOr = function (cond) {
                        return !$scope.isOwner() || (cond);
                      };

                      // Available member list on select box.
                      registeredMemberList = Object.keys($scope.currentRepo.perUserPermissions);
                      $scope.memberList = Object.keys(metadata.members).filter(function (value) {
                        return registeredMemberList.indexOf(value) === -1; // not exist
                      });

                      // Available token list on select box.
                      registeredAppIdList = Object.keys($scope.currentRepo.perTokenPermissions);
                      $scope.appIdList = Object.keys(metadata.tokens).filter(function (value) {
                        return registeredAppIdList.indexOf(value) === -1; // not exist
                      });

                      // Make permission tables.
                      $scope.rolePermTable = Permission.makePermissionTable($scope.currentRepo.perRolePermissions);
                      $scope.userPermTable = Permission.makePermissionTable($scope.currentRepo.perUserPermissions);
                      $scope.tokenPermTable = Permission.makePermissionTable($scope.currentRepo.perTokenPermissions);

                      // Make a copy of the permission table in order to check whether it is changed.
                      $scope.originalRolePermTable = angular.copy($scope.rolePermTable);
                      $scope.originalUserPermTable = angular.copy($scope.userPermTable);
                      $scope.originalTokenPermTable = angular.copy($scope.tokenPermTable);
                      $scope.rolePermTableChanged = false;
                    });
                  };

                  $scope.refresh();
                });

