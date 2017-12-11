/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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
namespace java com.linecorp.centraldogma.internal.thrift

typedef i32 RevisionNumber
typedef string Timestamp // ISO8601
typedef string EntryPath

const RevisionNumber HEAD = -1
const string DOGMA = "dogma"
const string META = "meta"

enum ErrorCode {
    UNIMPLEMENTED = 1;
    INTERNAL_SERVER_ERROR = 2;
    BAD_REQUEST = 3;
    PROJECT_NOT_FOUND = 4;
    PROJECT_EXISTS = 5;
    REPOSITORY_NOT_FOUND = 6;
    REPOSITORY_EXISTS = 7;
    REVISION_NOT_FOUND = 8;
    REVISION_EXISTS = 9;
    ENTRY_NOT_FOUND = 10;
    REDUNDANT_CHANGE = 11;
    CHANGE_CONFLICT = 12;
    QUERY_FAILURE = 13;
    SHUTTING_DOWN = 14;
}

enum EntryType {
    JSON = 1,
    TEXT = 2,
    DIRECTORY = 3,
}

enum ChangeType {
    UPSERT_JSON = 1,
    UPSERT_TEXT = 2,
    REMOVE = 3,
    RENAME = 4,
    APPLY_JSON_PATCH = 5,
    APPLY_TEXT_PATCH = 6,
}

enum PropertyType {
    REQUIRED = 1,
    DYNAMIC = 2,
}

enum Markup {
    UNKNOWN = 1,
    PLAINTEXT = 2,
    MARKDOWN = 3,
}

enum PluginOperationDataType {
    UNDEFINED = 1,
    BOOLEAN = 2,
    NUMBER = 3,
    STRING = 4,
    OBJECT = 5,
    ARRAY = 6,
}

exception CentralDogmaException {
    1: required ErrorCode errorCode,
    2: optional string message,
}

struct Entry {
    1: required EntryPath path,
    2: required EntryType type,
    3: optional string content,
}

struct Change {
    1: required EntryPath path,
    2: required ChangeType type,
    3: optional string content,
}

struct Revision {
    1: required RevisionNumber major,
    2: required RevisionNumber minor,
}

struct Author {
    1: required string name,
    2: required string email,
}

struct Comment {
    1: required string content,
    2: optional Markup markup = Markup.PLAINTEXT,
}

struct Commit {
    1: required Revision revision,
    2: required Author author,
    3: required Timestamp timestamp,
    4: required string summary,
    5: required Comment detail,
    6: required list<Change> diffs = [],
}

struct Repository {
    1: required string name,
    2: optional Commit head,
}

enum PropertyFilterType {
    JSON_PATH = 1,
}

struct PropertyFilter {
    1: required PropertyFilterType type,
    2: required string content,
}

struct SchemaEntry {
    1: required string repositoryName,
    2: required EntryPath path,
    3: required PropertyFilter propertyFilter,
    4: required list<PropertyType> types,
    5: optional Comment comment,
}

struct Schema {
    1: required list<SchemaEntry> entries,
}

struct PluginOperationParamDef {
    1: required string name,
    2: required PluginOperationDataType type,
    3: optional Comment comment,
}

struct PluginOperationReturnDef {
    1: required PluginOperationDataType type,
    2: optional Comment comment,
}

struct PluginOperation {
    1: required string pluginName,
    2: required string operationName,
    3: required list<PluginOperationParamDef> paramDefs,
    4: required PluginOperationReturnDef returnDef,
    5: optional Comment comment,
}

struct Plugin {
    1: required string name,
    2: required EntryPath path,
}

struct Project {
    1: required string name,
    3: optional Schema schema,
    4: optional list<Plugin> plugins = [],
}

enum QueryType {
    IDENTITY = 1,
    JSON_PATH = 2,
}

struct Query {
    1: required string path,
    2: required QueryType type,
    3: required list<string> expressions,
}

struct NamedQuery {
    1: required string name,
    2: required bool enabled,
    3: required string repositoryName,
    4: required Query query,
    5: optional Comment comment,
}

struct Subscriber {
    1: required string address,
    2: required i32 port,
}

struct GetFileResult {
    1: required EntryType type,
    2: required string content,
}

// TODO(trustin): Rename to GetDiffResult.
struct DiffFileResult {
    1: required ChangeType type,
    // FIXME(trustin): Make content optional.
    2: required string content,
}

struct WatchRepositoryResult {
    1: optional Revision revision,
}

const WatchRepositoryResult EMPTY_WATCH_REPOSITORY_RESULT = {}

struct WatchFileResult {
    1: optional Revision revision,
    2: optional EntryType type,
    3: optional string content,
}

const WatchFileResult EMPTY_WATCH_FILE_RESULT = {}

/**
 * Central Dogma Service
 */
service CentralDogmaService {

    /**
     * Creates a project.
     */
    void createProject(1: string name) throws (1: CentralDogmaException e),

    /**
     * Removes a project.
     */
    void removeProject(1: string name) throws (1: CentralDogmaException e),

    /**
     * Unremoves a project.
     */
    void unremoveProject(1: string name) throws (1: CentralDogmaException e),

    /**
     * Retrieves the list of the projects.
     */
    list<Project> listProjects() throws (1: CentralDogmaException e),

    /**
     * Retrieves the list of the removed projects.
     */
    set<string> listRemovedProjects() throws (1: CentralDogmaException e),

    /**
     * Creates a repository.
     */
    void createRepository(1: string projectName, 2: string repositoryName) throws (1: CentralDogmaException e),

    /**
     * Removes a repository.
     */
    void removeRepository(1: string projectName, 2: string repositoryName) throws (1: CentralDogmaException e),

    /**
     * Unremoves a repository.
     */
    void unremoveRepository(1: string projectName, 2: string repositoryName) throws (1: CentralDogmaException e),

    /**
     * Retrieves the list of the repositories.
     */
    list<Repository> listRepositories(1: string projectName) throws (1: CentralDogmaException e),

    /**
     * Retrieves the list of the removed repositories.
     */
    set<string> listRemovedRepositories(1: string projectName) throws (1: CentralDogmaException e),

    /**
     * Converts the relative revision number to the absolute revision number. (e.g. -1 -&gt; 3, -1.-1 -&gt; 3.4)
     */
    Revision normalizeRevision(1: string projectName, 2: string repositoryName, 3: Revision revision)
        throws (1: CentralDogmaException e),

    /**
     * Retrieves the list of the files in the path.
     */
    list<Entry> listFiles(1: string projectName, 2: string repositoryName, 3: Revision revision,
                          4: string pathPattern) throws (1: CentralDogmaException e),

    /**
     * Retrieves the files that match the path pattern.
     */
    list<Entry> getFiles(1: string projectName, 2: string repositoryName, 3: Revision revision, 4: string pathPattern)
        throws (1: CentralDogmaException e),

    /**
     * Retrieves the history of the repository.
     */
    list<Commit> getHistory(1: string projectName, 2: string repositoryName,
                            3: Revision fromRevision, 4: Revision toRevision,
                            5: string pathPattern) throws (1: CentralDogmaException e),

    /**
     * Retrieves the diffs matched by the path pattern from {@code from} to {@code to}.
     */
    list<Change> getDiffs(1: string projectName, 2: string repositoryName,
                          3: Revision fromRevision, 4: Revision toRevision,
                          5: string pathPattern) throws (1: CentralDogmaException e),

    /**
    * Retrieves preview diffs on {@code baseRevsion} for {@code changes}.
    */
    list<Change> getPreviewDiffs(1: string projectName, 2: string repositoryName, 3: Revision baseRevision,
                                 4: list<Change> changes) throws (1: CentralDogmaException e),

    /**
     * Pushes the changes to the repository.
     */
    Commit push(1: string projectName, 2: string repositoryName,
                3: Revision baseRevision, 4: Author author, 5: string summary, 6: Comment detail,
                7: list<Change> changes) throws (1: CentralDogmaException e),

    /**
     * Queries a file at the specified revision.
     */
    GetFileResult getFile(1: string projectName, 2: string repositoryName, 3: Revision revision,
                          4: Query query) throws (1: CentralDogmaException e),

    // FIXME(trustin): Rename to getDiff()
    /**
     * Queries a file at two different revisions and return the diff of the two query results.
     */
    DiffFileResult diffFile(1: string projectName, 2: string repositoryName,
                            3: Revision fromRevision, 4: Revision toRevision,
                            5: Query query) throws (1: CentralDogmaException e),

    /**
     * Awaits and returns the latest known revision since the specified revision.
     */
    WatchRepositoryResult watchRepository(
            1: string projectName, 2: string repositoryName, 3: Revision lastKnownRevision,
            4: string pathPattern, 5: i64 timeoutMillis) throws (1: CentralDogmaException e),

    /**
     * Awaits and returns the query result of the specified file since the specified last known revision.
     */
    WatchFileResult watchFile(1: string projectName, 2: string repositoryName, 3: Revision lastKnownRevision,
                              4: Query query, 5: i64 timeoutMillis) throws (1: CentralDogmaException e),

    // The operations below are not implemented yet.

    /**
     * Gets the schema.
     */
    Schema getSchema(1: string projectName) throws (1: CentralDogmaException e),

    /**
     * Saves the schema.
     */
    void saveSchema(1: string projectName, 2: Schema schema) throws (1: CentralDogmaException e),

    /**
     * Gets the named query.
     */
    NamedQuery getNamedQuery(1: string projectName, 2: string name) throws (1: CentralDogmaException e),

    /**
     * Saves the named query.
     */
    void saveNamedQuery(1: string projectName, 2: NamedQuery namedQuery) throws (1: CentralDogmaException e),

    /**
     * Removes the named query.
     */
    void removeNamedQuery(1: string projectName, 2: string name) throws (1: CentralDogmaException e),

    /**
     * Retrieves the list of the named queries.
     */
    list<NamedQuery> listNamedQueries(1: string projectName) throws (1: CentralDogmaException e),

    /**
     * Gets the plugin.
     */
    Plugin getPlugin(1: string projectName, 2: string pluginName) throws (1: CentralDogmaException e),

    /**
     * Saves the plugin.
     */
    void savePlugin(1: string projectName, 2: Plugin plugin) throws (1: CentralDogmaException e),

    /**
     * Removes the plugin.
     */
    void removePlugin(1: string projectName, 2: string pluginName) throws (1: CentralDogmaException e),

    /**
     * Retrieves the list of the plugins.
     */
    list<Plugin> listPlugins(1: string projectName) throws (1: CentralDogmaException e),

    /**
     * Retrieves the list of all operations provided by the plugin.
     */
    list<PluginOperation> listPluginOperations(1: string projectName) throws (1: CentralDogmaException e),

    /**
     * Performs the plugin operation.
     * <p>
     * <ul>
     *   <li>{@code params} is a JSON dictionary whose keys and values are the names and values
     *     of the parameters.</li>
     *   <li>The return value is one of the following:
     *     <ul>
     *       <li>a JSON object,</li>
     *       <li>a JSON array,</li>
     *       <li>a number,</li>
     *       <li>a boolean value ({@code true} or {@code false}) or</li>
     *       <li>{@code null}</li>
     *     </ul>
     *   </li>
     * </ul>
     * </p>
     */
    string performPluginOperation(
        1: string projectName, 2: string pluginName, 3: string operationName, 4: string params)
        throws (1: CentralDogmaException e),

    /**
     * Queries by the named query.
     */
    string queryByNamedQuery(1: string projectName, 2: string namedQuery, 3: Revision revision)
        throws (1: CentralDogmaException e),

    /**
     * Retrieves the list of the subscribers for the json file.
     */
    list<Subscriber> listSubscribers(1: string projectName, 2: string repositoryName, 3: EntryPath path)
        throws (1: CentralDogmaException e),
}
