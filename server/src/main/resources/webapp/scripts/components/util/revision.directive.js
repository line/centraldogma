'use strict';

angular.module('CentralDogmaAdmin')
    .directive('revision',
               function () {
                 return {
                   template: '<button type="button" class="btn btn-default"' +
                             '        uib-popover-template="templateUrl"' +
                             '        popover-placement="auto bottom-left"' +
                             '        popover-trigger="outsideClick">' +
                             '  {{ "entities.revision" | translate }}: ' +
                             '  <b>{{ specifiedRevision }}</b>' +
                             '  <span ng-show="normalizedRevision">({{ normalizedRevision }})</span>' +
                             '  <span class="caret"></span>' +
                             '</button>' +
                             '<script type="text/ng-template" id="revisionPopoverTemplate.html">' +
                             '  <div class="form-group">' +
                             '    <label for="revision" translate>entities.revision</label>' +
                             '    <a href="" ng-click="callCallback(-1)" translate>entities.go_to_head_revision</a>' +
                             '    <input type="text" class="form-control" id="target-revision"' +
                             '           ng-model="targetRevision" ng-keyup="keyUp($event)"' +
                             "           placeholder=\"{{ 'entities.revision_placeholder' | translate }}\">" +
                             '  </div>' +
                             '  <div class="commits" ng-show="hasHistory()">' +
                             '    <label translate>entities.recent_commits</label>' +
                             '    <ul>' +
                             '      <li ng-repeat="c in history">' +
                             '        <a href="" ng-click="callCallback(c.revision.major)"' +
                             '           uib-tooltip="{{c.revision.major}}: {{c.summary}}\nby {{c.author.name}} at {{c.timestamp}}"' +
                             '           tooltip-placement="auto top-left">' +
                             '          {{c.revision.major}}: ' +
                             '          {{c.summary}}</a>' +
                             '      </li>' +
                             '    </ul>' +
                             '  </div>' +
                             '</script>',
                   restrict: 'E',
                   scope: {
                     project: '<',
                     repository: '<',
                     path: '<',
                     revision: '<',
                     showInitialCommit: '<',
                     callback: '&'
                   },
                   controller: function ($scope, $timeout, RepositoryService) {
                     var maxCommits = 15; // The maximum number of adjacent commits to show.

                     $scope.templateUrl = 'revisionPopoverTemplate.html';
                     $scope.history = [];

                     $scope.keyUp = function (event) {
                       if (event.keyCode === 13) { // return
                         $scope.callCallback($('#target-revision').val());
                       }
                     };

                     $scope.callCallback = function (revision) {
                       if (angular.isNumber(revision)) {
                         if (revision === -1) {
                           $scope.callback()('head');
                         } else {
                           $scope.callback()(parseInt(revision).toString());
                         }
                         return;
                       }

                       revision = revision.toString().toLowerCase();
                       if (revision === 'head') {
                         $scope.callback()('head');
                         return;
                       }

                       var revisionAsInt = parseInt(revision);
                       if (Number.isNaN(revisionAsInt)) {
                         return;
                       }

                       if (revisionAsInt === -1) {
                         $scope.callback()('head');
                         return;
                       }

                       $scope.callback()(revisionAsInt.toString());
                     };

                     $scope.hasHistory = function () {
                       return $scope.history.length > 0;
                     };

                     var fetchHistory = function (fromRev) {
                       if (!angular.isDefined($scope.path)) {
                         return;
                       }

                       var toRev = Math.max(1, fromRev - maxCommits + 1);
                       RepositoryService.getHistory($scope.project, $scope.repository, $scope.path, fromRev,
                                                    toRev).then(function (result) {

                         if (!$scope.showInitialCommit &&
                             result.length > 0 && result[result.length - 1].revision.major === 1) {
                           $scope.history = result.slice(0, result.length - 1);
                         } else {
                           $scope.history = result;
                         }
                       });
                     };

                     // Focus the target-revision input field when it goes visible.
                     $scope.$watch(function() {
                       return angular.element('#target-revision').is(':visible');
                     }, function () {
                       $timeout(function() { angular.element('#target-revision').focus(); });
                     });

                     // Fetch the adjacent commits.
                     RepositoryService.normalizeRevision($scope.project, $scope.repository, -1).then(function (head) {

                       var headRev = head.major;
                       var fromRev;
                       var revisionAsInt = parseInt($scope.revision);
                       if (Number.isNaN(revisionAsInt)) {
                         revisionAsInt = -1;
                       }

                       if (revisionAsInt < 0) {
                         RepositoryService.normalizeRevision($scope.project, $scope.repository, revisionAsInt).then(function (normalized) {
                           var normalizedRev = normalized.major;
                           if (revisionAsInt !== -1) {
                             $scope.specifiedRevision = revisionAsInt;
                           } else {
                             $scope.specifiedRevision = 'HEAD';
                           }
                           $scope.normalizedRevision = normalizedRev;
                           fetchHistory(Math.min(headRev, normalizedRev + (maxCommits >> 1)));
                         });
                       } else {
                         $scope.specifiedRevision = revisionAsInt;
                         if (revisionAsInt === headRev) {
                           $scope.normalizedRevision = 'HEAD';
                         }
                         fromRev = Math.min(headRev, revisionAsInt + (maxCommits >> 1));
                         fetchHistory(fromRev);
                       }
                     });
                   }
                 };
               });
