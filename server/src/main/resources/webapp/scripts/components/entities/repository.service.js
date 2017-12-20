'use strict';

angular.module('CentralDogmaAdmin')
    .factory('RepositoryService',
             function (ApiService, ApiV1Service, StringUtil) {
               return {
                 createRepository: function (projectName, repositoryName) {
                   projectName = StringUtil.requireNotEmpty(projectName, 'projectName');
                   repositoryName = StringUtil.requireNotEmpty(repositoryName, 'repositoryName');

                   // NOTE: API v1 uses 'repos' instead of 'repositories'.
                   return ApiV1Service.post(StringUtil.encodeUri(['projects', projectName, 'repos']),
                     {name: repositoryName}
                   );
                 },

                 removeRepository: function (projectName, repositoryName) {
                   projectName = StringUtil.requireNotEmpty(projectName, 'projectName');
                   repositoryName = StringUtil.requireNotEmpty(repositoryName, 'repositoryName');

                   // NOTE: API v1 uses 'repos' instead of 'repositories'.
                   return ApiV1Service.delete(StringUtil.encodeUri(['projects', projectName,
                                                                    'repos', repositoryName]));
                 },

                 restoreRepository: function (projectName, repositoryName) {
                   projectName = StringUtil.requireNotEmpty(projectName, 'projectName');
                   repositoryName = StringUtil.requireNotEmpty(repositoryName, 'repositoryName');

                   // NOTE: API v1 uses 'repos' instead of 'repositories'.
                   return ApiV1Service.jsonPatch(StringUtil.encodeUri(['projects', projectName,
                                                                       'repos', repositoryName]),
                     EntitiesUtil.toReplaceJsonPatch('/status', 'active'));
                 },

                 listRepositories: function (projectName) {
                   projectName = StringUtil.requireNotEmpty(projectName, 'projectName');

                   // NOTE: API v1 uses 'repos' instead of 'repositories'.
                   return ApiV1Service.get(StringUtil.encodeUri(['projects', projectName, 'repos']));
                 },

                 normalizeRevision: function (projectName, repositoryName, revision) {
                   projectName = StringUtil.requireNotEmpty(projectName, 'projectName');
                   repositoryName = StringUtil.requireNotEmpty(repositoryName, 'repositoryName');
                   if (!angular.isNumber(revision)) {
                     revision = StringUtil.requireNotEmpty(revision, 'revision');
                   }

                   var sb = [];
                   sb.push('projects/');
                   sb.push(StringUtil.encodeParam(projectName));
                   sb.push('/repositories/');
                   sb.push(StringUtil.encodeParam(repositoryName));
                   sb.push('/revision/');
                   sb.push(revision);

                   return ApiService.get(sb.join(''));
                 },

                 getTree: function (projectName, repositoryName, revision, path) {
                   projectName = StringUtil.requireNotEmpty(projectName, 'projectName');
                   repositoryName = StringUtil.requireNotEmpty(repositoryName, 'repositoryName');
                   revision = StringUtil.requireNotEmpty(revision, 'revision');
                   path = StringUtil.requireNotEmpty(path, 'path');

                   return ApiV1Service.get(StringUtil.encodeUri(['projects', projectName,
                                                                 'repos', repositoryName,
                                                                 'tree', path]));
                 },

                 getFile: function (projectName, repositoryName, revision, query) {
                   projectName = StringUtil.requireNotEmpty(projectName, 'projectName');
                   repositoryName = StringUtil.requireNotEmpty(repositoryName, 'repositoryName');
                   revision = StringUtil.requireNotEmpty(revision, 'revision');

                   var path = StringUtil.requireNotEmpty(query.path, 'path');
                   var queryType = StringUtil.defaultString(query.type, 'IDENTITY');
                   var queryExpressions = angular.isArray(query.expressions) ? query.expressions : [];

                   var sb = [];
                   sb.push('projects/');
                   sb.push(StringUtil.encodeParam(projectName));
                   sb.push('/repositories/');
                   sb.push(StringUtil.encodeParam(repositoryName));
                   sb.push('/files/revisions/');
                   sb.push(revision);
                   sb.push(StringUtil.encodePath(path)); // path starts with '/'

                   var params = ['queryType=' + queryType];
                   if (angular.isArray(queryExpressions)) {
                     for (var idx in queryExpressions) {
                       params.push('&expression=');
                       params.push(StringUtil.encodeParam(queryExpressions[idx]));
                     }
                   }

                   if (params.length > 0) {
                     sb.push('?');
                     sb.push(params.join(''));
                   }

                   return ApiService.get(sb.join(''));
                 },

                 addFile: function (projectName, repositoryName, revision, commitMessage, file) {
                   return this.saveFile(projectName, repositoryName, revision, commitMessage, file, 'ADD');
                 },

                 editFile: function (projectName, repositoryName, revision, commitMessage, file) {
                   return this.saveFile(projectName, repositoryName, revision, commitMessage, file, 'EDIT');
                 },

                 saveFile: function (projectName, repositoryName, revision, commitMessage, file, saveMode) {
                   projectName = StringUtil.requireNotEmpty(projectName, 'projectName');
                   repositoryName = StringUtil.requireNotEmpty(repositoryName, 'repositoryName');
                   revision = StringUtil.requireNotEmpty(revision, 'revision');

                   if (angular.isUndefined(file)) {
                     throw new Error('undefined file');
                   }
                   if (angular.isUndefined(commitMessage)) {
                     throw new Error('undefined commitMessage');
                   }

                   var sb = [];
                   sb.push('projects/');
                   sb.push(StringUtil.encodeParam(projectName));
                   sb.push('/repositories/');
                   sb.push(StringUtil.encodeParam(repositoryName));
                   sb.push('/files/revisions/');
                   sb.push(revision);

                   switch (saveMode) {
                   case 'ADD':
                     return ApiService.post(sb.join(''), {
                       file: file,
                       commitMessage: commitMessage
                     });
                     break;
                   case 'EDIT':
                     return ApiService.put(sb.join(''), {
                       file: file,
                       commitMessage: commitMessage
                     });
                     break;
                   default:
                     throw new Error('unsupported saveMode' + saveMode);
                   }
                 },

                 deleteFile: function (projectName, repositoryName, revision, commitMessage, path) {
                   projectName = StringUtil.requireNotEmpty(projectName, 'projectName');
                   repositoryName = StringUtil.requireNotEmpty(repositoryName, 'repositoryName');
                   revision = StringUtil.requireNotEmpty(revision, 'revision');
                   path = StringUtil.requireNotEmpty(path, 'path');

                   if (angular.isUndefined(commitMessage)) {
                     throw new Error('undefined commitMessage');
                   }

                   var sb = [];
                   sb.push('projects/');
                   sb.push(StringUtil.encodeParam(projectName));
                   sb.push('/repositories/');
                   sb.push(StringUtil.encodeParam(repositoryName));
                   sb.push('/delete/revisions/');
                   sb.push(revision);
                   sb.push(StringUtil.encodePath(path)); // path starts with '/'

                   return ApiService.post(sb.join(''), {
                     commitMessage: commitMessage
                   });
                 },

                 getHistory: function (projectName, repositoryName, path, fromRevision, toRevision) {
                   projectName = StringUtil.requireNotEmpty(projectName, 'projectName');
                   repositoryName = StringUtil.requireNotEmpty(repositoryName, 'repositoryName');
                   path = StringUtil.requireNotEmpty(path, 'path');

                   if (!angular.isNumber(fromRevision)) {
                     fromRevision = StringUtil.requireNotEmpty(fromRevision, 'fromRevision');
                   }
                   if (!angular.isNumber(toRevision)) {
                     toRevision = StringUtil.requireNotEmpty(toRevision, 'toRevision');
                   }

                   var sb = [];
                   sb.push('projects/');
                   sb.push(StringUtil.encodeParam(projectName));
                   sb.push('/repositories/');
                   sb.push(StringUtil.encodeParam(repositoryName));
                   sb.push('/history');
                   sb.push(StringUtil.encodePath(path)); // path starts with '/'
                   sb.push("?from=");
                   sb.push(fromRevision);
                   sb.push("&to=");
                   sb.push(toRevision);

                   return ApiService.get(sb.join(''));
                 },

                 search: function (projectName, repositoryName, revision, term) {
                   projectName = StringUtil.requireNotEmpty(projectName, 'projectName');
                   repositoryName = StringUtil.requireNotEmpty(repositoryName, 'repositoryName');
                   revision = StringUtil.requireNotEmpty(revision, 'revision');
                   term = StringUtil.requireNotEmpty(term, 'term');

                   var sb = [];
                   sb.push('projects/');
                   sb.push(StringUtil.encodeParam(projectName));
                   sb.push('/repositories/');
                   sb.push(StringUtil.encodeParam(repositoryName));
                   sb.push('/search/revisions/');
                   sb.push(revision);
                   sb.push('?term=');
                   sb.push(StringUtil.encodeParam(term));

                   return ApiService.get(sb.join(''));
                 },

                 parsePath: function (path) {
                   path = StringUtil.requireNotEmpty(path, 'path');

                   var ret = [{name: 'root', path: '/'}];

                   var split = path.split('/');
                   for (var i = 0; i < split.length; i++) {
                     if (split[i] === '') {
                       continue;
                     }

                     var temp = [];
                     for (var j = 0; j <= i; j++) {
                       temp.push(split[j]);
                     }

                     ret.push({
                       'name': split[i],
                       'path': temp.join('/')
                     });
                   }

                   return ret;
                 }
               };
             });
