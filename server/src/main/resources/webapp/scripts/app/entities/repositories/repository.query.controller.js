'use strict';

angular.module('CentralDogmaAdmin')
    .controller('RepositoryQueryController',
                function ($scope, $state, $stateParams, $location, $window,
                          CentralDogmaConstant, RepositoryService, StringUtil) {

                  $scope.project = {
                    name: $stateParams.projectName
                  };
                  $scope.repository = {
                    name: $stateParams.repositoryName
                  };
                  $scope.revision = StringUtil.isEmpty($stateParams.revision) ?
                                    CentralDogmaConstant.HEAD : $stateParams.revision;

                  $scope.path = StringUtil.normalizePath($stateParams.path);
                  $scope.parsedPaths = RepositoryService.parsePath($scope.path);

                  $scope.file = null;
                  $scope.queryResult = '';

                  var filterInvalidExpressions = function (expressions) {
                    var ret = [];
                    for (var index in expressions) {
                      if (StringUtil.isEmpty(expressions[index].value)) {
                        continue;
                      }
                      ret.push(expressions[index]);
                    }
                    return ret;
                  };

                  var convertExpressionsIntoArray = function (expressions) {
                    var filtered = filterInvalidExpressions(expressions);

                    var ret = [];
                    for (var index in filtered) {
                      ret.push(filtered[index].value);
                    }
                    return ret;
                  };

                  $scope.queryType = 'JSON_PATH';

                  if (angular.isString($stateParams.expression)) {
                    $scope.expressions = [{value: $stateParams.expression}];
                  } else if (angular.isArray($stateParams.expression)) {
                    var expressions = [];

                    for (var index in $stateParams.expression) {
                      expressions.push({value: $stateParams.expression[index]});
                    }

                    $scope.expressions = filterInvalidExpressions(expressions);
                  } else {
                    $scope.expressions = [];
                  }

                  $scope.addNewExpression = function () {
                    $scope.expressions.push({value: ''});
                  };

                  $scope.removeExpression = function (index) {
                    $scope.expressions.splice(index, 1);
                  };

                  if ($scope.expressions.length == 0) {
                    $scope.addNewExpression();
                  }

                  $scope.setRevision = function (revision) {
                    $location.path('/' + $scope.project.name + '/' + $scope.repository.name +
                                   '/query/' + revision + $scope.path);
                  };

                  $scope.query = function () {
                    $state.go('repositoryQuery', {
                      projectName: $scope.project.name,
                      repositoryName: $scope.repository.name,
                      revision: $scope.revision,
                      expression: convertExpressionsIntoArray($scope.expressions)
                    });
                  };

                  var converted = convertExpressionsIntoArray($scope.expressions);
                  if (converted.length == 0) {
                    RepositoryService.getFile($scope.project.name, $scope.repository.name, $scope.revision,
                        {
                          path: $scope.path,
                          type: 'IDENTITY'
                        }).then(
                        function (fileWithRevision) {
                          $scope.file = fileWithRevision.file;
                          $scope.queryResult = JSON.stringify(JSON.parse(fileWithRevision.file.content), null, 2) + '\n';
                        });
                  } else {
                    RepositoryService.getFile($scope.project.name, $scope.repository.name, $scope.revision,
                        {
                          path: $scope.path,
                          type: $scope.queryType,
                          expressions: converted
                        }).then(
                        function (fileWithRevision) {
                          $scope.file = fileWithRevision.file;
                          $scope.queryResult = JSON.stringify(JSON.parse(fileWithRevision.file.content), null, 2) + '\n';
                        });
                  }
                });
