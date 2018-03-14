'use strict';

angular.module('CentralDogmaAdmin')
    .controller('MetadataProjectController',
                function ($scope, $state, $stateParams, ApiService, ApiV1Service, $location, $window, $uibModal,
                          SettingsService, ConfirmationDialog, IdentifierWithRole, EntitiesUtil,
                          ProjectService, RepositoryService, Principal, CentralDogmaConstant,
                          NotificationUtil, StringUtil, Security) {
                  $scope.project = {
                    name: $stateParams.projectName,
                    roles: [
                      'OWNER', 'MEMBER'
                    ]
                  };
                  $scope.toDateTimeStr = EntitiesUtil.toDateTimeStr;
                  $scope.sanitizeEmail = EntitiesUtil.sanitizeEmail;

                  $scope.removeProject = function () {
                    ConfirmationDialog.openModal('entities.title_remove_project', {
                      target: $scope.project.name
                    }).then(function () {
                      ProjectService.removeProject($scope.project.name).then(function () {
                        NotificationUtil.success('entities.title_project_removed', {
                          target: $scope.project.name
                        });
                        $location.url('/projects');
                      });
                    });
                  };

                  $scope.removeRepository = function (repo) {
                    ConfirmationDialog.openModal('entities.title_remove_repository', {
                      target: repo.name
                    }).then(function () {
                      RepositoryService.removeRepository($scope.project.name, repo.name).then(function () {
                        $scope.refresh();
                      });
                    });
                  };
                  $scope.restoreRepository = function (repo) {
                    ConfirmationDialog.openModal('entities.title_restore_repository', {
                      target: repo.name
                    }).then(function () {
                      RepositoryService.restoreRepository($scope.project.name, repo.name).then(function () {
                        $scope.refresh();
                      });
                    });
                  };

                  // Member List.
                  $scope.updateMemberRole = function (member) {
                    ConfirmationDialog.openModal('entities.title_update_member', {
                      target: EntitiesUtil.sanitizeEmail(member.login)
                    }).then(function () {
                      ApiV1Service.jsonPatch(StringUtil.encodeUri(['metadata', $scope.project.name,
                                                                   'members', member.login]),
                        EntitiesUtil.toReplaceJsonPatch('/role', member.role)).then(function () {
                        $scope.refresh();
                      }, function (error) {
                        NotificationUtil.error(error);
                      });
                    });
                  };
                  $scope.removeMember = function (member) {
                    ConfirmationDialog.openModal('entities.title_remove_member', {
                      target: EntitiesUtil.sanitizeEmail(member.login)
                    }).then(function () {
                      ApiV1Service.delete(StringUtil.encodeUri(['metadata', $scope.project.name,
                                                                'members', member.login])).then(function () {
                        $scope.refresh();
                      });
                    });
                  };

                  // New member list.
                  $scope.newMemberList = new IdentifierWithRole();
                  $scope.saveNewMember = function (newMember) {
                    const duplicated = $scope.memberList.filter(function (value) {
                      return angular.equals(value.login, newMember.id);
                    });
                    if (duplicated.length > 0) {
                      NotificationUtil.error('entities.title_target_already_exists', {
                        target: newMember.id
                      });
                      return;
                    }
                    ConfirmationDialog.openModal('entities.title_save_member', {
                      target: newMember.id
                    }).then(function () {
                      ApiV1Service.post(StringUtil.encodeUri(['metadata', $scope.project.name,
                                                              'members']), newMember).then(function () {
                        $scope.newMemberList.remove(newMember);
                        $scope.refresh();
                      }, function (error) {
                        NotificationUtil.error(error);
                      });
                    });
                  };

                  // Token list.
                  $scope.updateTokenRole = function (token) {
                    ConfirmationDialog.openModal('entities.title_update_token', {
                      target: token.appId
                    }).then(function () {
                      ApiV1Service.jsonPatch(StringUtil.encodeUri(['metadata', $scope.project.name,
                                                                   'tokens', token.appId]),
                        EntitiesUtil.toReplaceJsonPatch('/role', token.role)).then(function () {
                        $scope.refresh();
                      }, function (error) {
                        NotificationUtil.error(error);
                      });
                    });
                  };
                  $scope.removeToken = function (token) {
                    ConfirmationDialog.openModal('entities.title_remove_token', {
                      target: token.appId
                    }).then(function () {
                      ApiV1Service.delete(StringUtil.encodeUri(['metadata', $scope.project.name,
                                                                'tokens', token.appId])).then(function () {
                        $scope.refresh();
                      }, function (error) {
                        NotificationUtil.error(error);
                      });
                    });
                  };

                  // New token list.
                  $scope.newTokenList = new IdentifierWithRole();
                  $scope.saveNewToken = function (newToken) {
                    ConfirmationDialog.openModal('entities.title_save_token', {
                      target: newToken.id
                    }).then(function () {
                      ApiV1Service.post(StringUtil.encodeUri(['metadata', $scope.project.name,
                                                              'tokens']), newToken).then(function () {
                        $scope.newTokenList.remove(newToken);
                        $scope.refresh();
                      }, function (error) {
                        NotificationUtil.error(error);
                      });
                    });
                  };

                  $scope.refresh = function() {
                    function refresh0(tokens) {
                      ApiV1Service.get(StringUtil.encodeUri(['projects', $scope.project.name])).then(function (metadata) {
                        var addedAppIds, allAppIds;

                        // Mark whether each repository is active or not.
                        Object.entries(metadata.repos).forEach(function (entry) {
                          entry[1].isActive = angular.isUndefined(entry[1].removal) ||
                                              entry[1].removal === null;
                        });

                        $scope.metadata = metadata;
                        $scope.tokens = tokens;
                        $scope.role = Principal.projectRole(metadata);
                        $scope.isOwner = function () {
                          return angular.equals($scope.role, CentralDogmaConstant.PROJECT_ROLE_OWNER);
                        };
                        $scope.isNotOwner = function () {
                          return !$scope.isOwner();
                        };
                        $scope.isNotOwnerOr = function (cond) {
                          return !$scope.isOwner() || cond;
                        };

                        // Prepare member and token list.
                        function convertList(list) {
                          var outputList = [];
                          if (angular.isUndefined(list) || list === null) {
                            return outputList;
                          }
                          Object.entries(list).forEach(function (entry) {
                            entry[1].isEditing = false;
                            entry[1].originalRole = entry[1].role;
                            outputList.push(entry[1]);
                          });
                          return outputList;
                        }
                        $scope.memberList = convertList(metadata.members);
                        $scope.tokenList = convertList(metadata.tokens);

                        addedAppIds = EntitiesUtil.toKeySet(metadata.tokens);
                        allAppIds = tokens.filter(function (t) {
                          return t.isActive;
                        }).map(function (t) {
                          return t.appId;
                        });
                        $scope.appIdList = EntitiesUtil.toUniqueSet(allAppIds, addedAppIds);
                      }, function (error) {
                        NotificationUtil.error(error);
                        $location.url('/projects');
                      });
                    }

                    Security.resolve().then(function (isEnabled) {
                      if (isEnabled) {
                        SettingsService.listTokens().then(function (tokens) {
                          refresh0(tokens);
                        });
                      } else {
                        refresh0([]);
                      }
                    });
                  };

                  $scope.refresh();
                });
