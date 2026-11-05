<!-- Generator: Widdershins v4.0.1 -->

<h1 id="house-tables-api">House Tables API v0.0.1</h1>

> Scroll down for code samples, example requests and responses. Select a language for code samples from the tabs above or the mobile navigation menu.

API description for House Tables API

Base URLs:

* <a href="http://localhost:8001">http://localhost:8001</a>

<a href="http://swagger.io/terms">Terms of service</a>

License: <a href="http://springdoc.org">Apache 2.0</a>

<h1 id="house-tables-api-usertable">UserTable</h1>

## Get User Table identified by databaseID and tableId.

<a id="opIdgetUserTable"></a>

> Code samples

`GET /hts/tables`

Returns a User House Table identified by databaseID and tableId.

<h3 id="get-user-table-identified-by-databaseid-and-tableid.-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|query|string|true|none|
|tableId|query|string|true|none|

> Example responses

> 200 Response

```json
{
  "entity": {
    "deletedAtMs": 1751907524,
    "purgeAfterMs": 1751907524,
    "tableId": "my_table",
    "databaseId": "my_database",
    "tableVersion": "string",
    "metadataLocation": "string",
    "storageType": "hdfs",
    "creationTime": 1651002318265
  }
}
```

<h3 id="get-user-table-identified-by-databaseid-and-tableid.-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|User Table GET: OK|[EntityResponseBodyUserTable](#schemaentityresponsebodyusertable)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|User Table GET: TBL_DB_NOT_FOUND|[EntityResponseBodyUserTable](#schemaentityresponsebodyusertable)|

<aside class="success">
This operation does not require authentication
</aside>

## Update a User Table

<a id="opIdputUserTable"></a>

> Code samples

`PUT /hts/tables`

Updates or creates a User House Table identified by databaseID and tableId. If the table does not exist, it will be created. If the table exists, it will be updated.

> Body parameter

```json
{
  "entity": {
    "deletedAtMs": 1751907524,
    "purgeAfterMs": 1751907524,
    "tableId": "my_table",
    "databaseId": "my_database",
    "tableVersion": "string",
    "metadataLocation": "string",
    "storageType": "hdfs",
    "creationTime": 1651002318265
  }
}
```

<h3 id="update-a-user-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|body|body|[CreateUpdateEntityRequestBodyUserTable](#schemacreateupdateentityrequestbodyusertable)|true|none|

> Example responses

> 200 Response

```json
{
  "entity": {
    "deletedAtMs": 1751907524,
    "purgeAfterMs": 1751907524,
    "tableId": "my_table",
    "databaseId": "my_database",
    "tableVersion": "string",
    "metadataLocation": "string",
    "storageType": "hdfs",
    "creationTime": 1651002318265
  }
}
```

<h3 id="update-a-user-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|User Table PUT: UPDATED|[EntityResponseBodyUserTable](#schemaentityresponsebodyusertable)|
|201|[Created](https://tools.ietf.org/html/rfc7231#section-6.3.2)|User Table PUT: CREATED|[EntityResponseBodyUserTable](#schemaentityresponsebodyusertable)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|User Table PUT: BAD_REQUEST|[EntityResponseBodyUserTable](#schemaentityresponsebodyusertable)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|User Table PUT: DB_NOT_FOUND|[EntityResponseBodyUserTable](#schemaentityresponsebodyusertable)|
|409|[Conflict](https://tools.ietf.org/html/rfc7231#section-6.5.8)|User Table PUT: CONFLICT|[EntityResponseBodyUserTable](#schemaentityresponsebodyusertable)|

<aside class="success">
This operation does not require authentication
</aside>

## Delete a User Table

<a id="opIddeleteTable"></a>

> Code samples

`DELETE /v1/hts/tables`

Delete a User House Table entry identified by databaseID and tableId. Soft delete will remove the User House Table entry and store it in Soft Deleted User Table. with additional metadata deletedAtMs and purgeAfterMs. Tables soft deleted will be configured with a TTL (purgeAfterMs) to be permanently deleted unless restored.

<h3 id="delete-a-user-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|query|string|true|none|
|tableId|query|string|true|none|
|isSoftDelete|query|boolean|true|none|

<h3 id="delete-a-user-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|204|[No Content](https://tools.ietf.org/html/rfc7231#section-6.3.5)|User Table DELETE: NO_CONTENT|None|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|User Table DELETE: BAD_REQUEST|None|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|User Table DELETE: TBL_DB_NOT_FOUND|None|

<aside class="success">
This operation does not require authentication
</aside>

## Restore a Soft Deleted User Table

<a id="opIdrestoreUserTable"></a>

> Code samples

`PUT /hts/tables/restore`

Restores an existing soft-deleted User House Table identified by databaseID, tableId, and deletedAtMs.  Will fail if the table's databaseId and tableId is currently in use.

<h3 id="restore-a-soft-deleted-user-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|query|string|true|none|
|tableId|query|string|true|none|
|deletedAtMs|query|integer(int64)|true|none|

> Example responses

> 204 Response

<h3 id="restore-a-soft-deleted-user-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|204|[No Content](https://tools.ietf.org/html/rfc7231#section-6.3.5)|User Table PUT: NO_CONTENT|[EntityResponseBodyUserTable](#schemaentityresponsebodyusertable)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|User Table PUT: BAD_REQUEST|[EntityResponseBodyUserTable](#schemaentityresponsebodyusertable)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|User Table PUT: TBL_DB_NOT_FOUND|[EntityResponseBodyUserTable](#schemaentityresponsebodyusertable)|
|409|[Conflict](https://tools.ietf.org/html/rfc7231#section-6.5.8)|User Table PUT: CONFLICT|[EntityResponseBodyUserTable](#schemaentityresponsebodyusertable)|

<aside class="success">
This operation does not require authentication
</aside>

## Rename a User Table

<a id="opIdrenameTable"></a>

> Code samples

`PATCH /hts/tables/rename`

Update an existing user table, identified by databaseID and tableId, to a new databaseID and tableId.

<h3 id="rename-a-user-table-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|fromDatabaseId|query|string|true|none|
|fromTableId|query|string|true|none|
|toDatabaseId|query|string|true|none|
|toTableId|query|string|true|none|
|metadataLocation|query|string|true|none|

<h3 id="rename-a-user-table-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|204|[No Content](https://tools.ietf.org/html/rfc7231#section-6.3.5)|User Table PATCH: NO_CONTENT|None|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|User Table PATCH: BAD_REQUEST|None|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|User Table PATCH: TBL_DB_NOT_FOUND|None|
|409|[Conflict](https://tools.ietf.org/html/rfc7231#section-6.5.8)|User Table PATCH: CONFLICT|None|

<aside class="success">
This operation does not require authentication
</aside>

## Search User Table by filter.

<a id="opIdgetUserTables"></a>

> Code samples

`GET /hts/tables/query`

Returns user table from house table that fulfills the predicate. For examples, one could provide {databaseId: d1} in the map to query all tables from database d1.

<h3 id="search-user-table-by-filter.-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|parameters|query|object|true|none|

> Example responses

> 200 Response

```json
{
  "results": [
    {
      "deletedAtMs": 1751907524,
      "purgeAfterMs": 1751907524,
      "tableId": "my_table",
      "databaseId": "my_database",
      "tableVersion": "string",
      "metadataLocation": "string",
      "storageType": "hdfs",
      "creationTime": 1651002318265
    }
  ],
  "pageResults": {
    "totalPages": 0,
    "totalElements": 0,
    "size": 0,
    "content": [
      {
        "deletedAtMs": 1751907524,
        "purgeAfterMs": 1751907524,
        "tableId": "my_table",
        "databaseId": "my_database",
        "tableVersion": "string",
        "metadataLocation": "string",
        "storageType": "hdfs",
        "creationTime": 1651002318265
      }
    ],
    "number": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "numberOfElements": 0,
    "pageable": {
      "offset": 0,
      "sort": {
        "empty": true,
        "sorted": true,
        "unsorted": true
      },
      "pageNumber": 0,
      "pageSize": 0,
      "paged": true,
      "unpaged": true
    },
    "first": true,
    "last": true,
    "empty": true
  }
}
```

<h3 id="search-user-table-by-filter.-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|User Table GET: OK|[GetAllEntityResponseBodyUserTable](#schemagetallentityresponsebodyusertable)|

<aside class="success">
This operation does not require authentication
</aside>

## Get Soft Deleted User Tables

<a id="opIdgetSoftDeletedUserTables"></a>

> Code samples

`GET /hts/tables/querySoftDeleted`

Returns paginated soft deleted user tables given databaseId, and/or tableId, and/or purgeAfterMs. If purgeAfterMs is provided, it will return all soft deleted tables that expire before the given timestamp

<h3 id="get-soft-deleted-user-tables-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|query|string|true|none|
|tableId|query|string|false|none|
|purgeAfterMs|query|integer(int64)|false|none|
|page|query|integer(int32)|false|none|
|size|query|integer(int32)|false|none|
|sortBy|query|string|false|none|

> Example responses

> 200 Response

<h3 id="get-soft-deleted-user-tables-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|User Table GET: OK|[GetAllEntityResponseBodyUserTable](#schemagetallentityresponsebodyusertable)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|User Table GET: BAD_REQUEST|[GetAllEntityResponseBodyUserTable](#schemagetallentityresponsebodyusertable)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|User Table GET: TBL_DB_NOT_FOUND|[GetAllEntityResponseBodyUserTable](#schemagetallentityresponsebodyusertable)|

<aside class="success">
This operation does not require authentication
</aside>

## Purge Soft Deleted User Tables older than purgeAfterMs

<a id="opIdpurgeSoftDeletedUserTables"></a>

> Code samples

`DELETE /hts/tables/purge`

Permanently deletes existing Soft Deleted User Tables older than purgeAfterMs that match databaseId and tableId. If purgeAfterMs is not provided, it will delete all soft deleted tables for the given table id

<h3 id="purge-soft-deleted-user-tables-older-than-purgeafterms-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|query|string|true|none|
|tableId|query|string|true|none|
|purgeAfterMs|query|integer(int64)|false|none|

<h3 id="purge-soft-deleted-user-tables-older-than-purgeafterms-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|204|[No Content](https://tools.ietf.org/html/rfc7231#section-6.3.5)|User Table PUT: NO_CONTENT|None|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|User Table PUT: BAD_REQUEST|None|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|User Table PUT: TBL_DB_NOT_FOUND|None|

<aside class="success">
This operation does not require authentication
</aside>

<h1 id="house-tables-api-job">Job</h1>

## Get job details for a given jobId.

<a id="opIdgetJob"></a>

> Code samples

`GET /hts/jobs`

Returns a Job entity identified by jobId

<h3 id="get-job-details-for-a-given-jobid.-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|jobId|query|string|true|none|

> Example responses

> 200 Response

```json
{
  "entity": {
    "jobId": "24efc962-9962-4522-b0b6-29490d7d8a0e",
    "state": "QUEUED",
    "version": "539482",
    "jobName": "my_job",
    "clusterId": "my_cluster",
    "creationTimeMs": 1651002318265,
    "startTimeMs": 1651002318265,
    "finishTimeMs": 1651002318265,
    "lastUpdateTimeMs": 1651002318265,
    "jobConf": "{'jobType': 'RETENTION', 'table': 'db.tb'}",
    "heartbeatTimeMs": 1651002318265,
    "executionId": "application_1642969576960_13278206",
    "engineType": "LIVY",
    "retentionTimeSec": 1745908497
  }
}
```

<h3 id="get-job-details-for-a-given-jobid.-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Job GET: OK|[EntityResponseBodyJob](#schemaentityresponsebodyjob)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Job GET: NOT_FOUND|[EntityResponseBodyJob](#schemaentityresponsebodyjob)|

<aside class="success">
This operation does not require authentication
</aside>

## Update a Job

<a id="opIdputJob"></a>

> Code samples

`PUT /hts/jobs`

Updates or creates a Job in the House Table and returns the job resources. If the job does not exist, it will be created. If the job exists, it will be updated.

> Body parameter

```json
{
  "entity": {
    "jobId": "24efc962-9962-4522-b0b6-29490d7d8a0e",
    "state": "QUEUED",
    "version": "539482",
    "jobName": "my_job",
    "clusterId": "my_cluster",
    "creationTimeMs": 1651002318265,
    "startTimeMs": 1651002318265,
    "finishTimeMs": 1651002318265,
    "lastUpdateTimeMs": 1651002318265,
    "jobConf": "{'jobType': 'RETENTION', 'table': 'db.tb'}",
    "heartbeatTimeMs": 1651002318265,
    "executionId": "application_1642969576960_13278206",
    "engineType": "LIVY",
    "retentionTimeSec": 1745908497
  }
}
```

<h3 id="update-a-job-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|body|body|[CreateUpdateEntityRequestBodyJob](#schemacreateupdateentityrequestbodyjob)|true|none|

> Example responses

> 200 Response

```json
{
  "entity": {
    "jobId": "24efc962-9962-4522-b0b6-29490d7d8a0e",
    "state": "QUEUED",
    "version": "539482",
    "jobName": "my_job",
    "clusterId": "my_cluster",
    "creationTimeMs": 1651002318265,
    "startTimeMs": 1651002318265,
    "finishTimeMs": 1651002318265,
    "lastUpdateTimeMs": 1651002318265,
    "jobConf": "{'jobType': 'RETENTION', 'table': 'db.tb'}",
    "heartbeatTimeMs": 1651002318265,
    "executionId": "application_1642969576960_13278206",
    "engineType": "LIVY",
    "retentionTimeSec": 1745908497
  }
}
```

<h3 id="update-a-job-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Job PUT: UPDATED|[EntityResponseBodyJob](#schemaentityresponsebodyjob)|
|201|[Created](https://tools.ietf.org/html/rfc7231#section-6.3.2)|Job PUT: CREATED|[EntityResponseBodyJob](#schemaentityresponsebodyjob)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Job PUT: BAD_REQUEST|[EntityResponseBodyJob](#schemaentityresponsebodyjob)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Job PUT: DB_NOT_FOUND|[EntityResponseBodyJob](#schemaentityresponsebodyjob)|
|409|[Conflict](https://tools.ietf.org/html/rfc7231#section-6.5.8)|Job PUT: CONFLICT|[EntityResponseBodyJob](#schemaentityresponsebodyjob)|

<aside class="success">
This operation does not require authentication
</aside>

## Delete a Job

<a id="opIddeleteJob"></a>

> Code samples

`DELETE /hts/jobs`

Delete a Job entity identified by jobId

<h3 id="delete-a-job-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|jobId|query|string|true|none|

<h3 id="delete-a-job-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|204|[No Content](https://tools.ietf.org/html/rfc7231#section-6.3.5)|Job DELETE: NO_CONTENT|None|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Job DELETE: BAD_REQUEST|None|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Job DELETE: NOT_FOUND|None|

<aside class="success">
This operation does not require authentication
</aside>

## Search Jobs by filter

<a id="opIdgetAllJobs"></a>

> Code samples

`GET /hts/jobs/query`

Returns jobs that fulfills the filter predicate. For examples, one could provide {status: <QUEUED>} in the map to query all jobs with that status. 

<h3 id="search-jobs-by-filter-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|parameters|query|object|true|none|

> Example responses

> 200 Response

```json
{
  "results": [
    {
      "jobId": "24efc962-9962-4522-b0b6-29490d7d8a0e",
      "state": "QUEUED",
      "version": "539482",
      "jobName": "my_job",
      "clusterId": "my_cluster",
      "creationTimeMs": 1651002318265,
      "startTimeMs": 1651002318265,
      "finishTimeMs": 1651002318265,
      "lastUpdateTimeMs": 1651002318265,
      "jobConf": "{'jobType': 'RETENTION', 'table': 'db.tb'}",
      "heartbeatTimeMs": 1651002318265,
      "executionId": "application_1642969576960_13278206",
      "engineType": "LIVY",
      "retentionTimeSec": 1745908497
    }
  ],
  "pageResults": {
    "totalPages": 0,
    "totalElements": 0,
    "size": 0,
    "content": [
      {
        "jobId": "24efc962-9962-4522-b0b6-29490d7d8a0e",
        "state": "QUEUED",
        "version": "539482",
        "jobName": "my_job",
        "clusterId": "my_cluster",
        "creationTimeMs": 1651002318265,
        "startTimeMs": 1651002318265,
        "finishTimeMs": 1651002318265,
        "lastUpdateTimeMs": 1651002318265,
        "jobConf": "{'jobType': 'RETENTION', 'table': 'db.tb'}",
        "heartbeatTimeMs": 1651002318265,
        "executionId": "application_1642969576960_13278206",
        "engineType": "LIVY",
        "retentionTimeSec": 1745908497
      }
    ],
    "number": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "numberOfElements": 0,
    "pageable": {
      "offset": 0,
      "sort": {
        "empty": true,
        "sorted": true,
        "unsorted": true
      },
      "pageNumber": 0,
      "pageSize": 0,
      "paged": true,
      "unpaged": true
    },
    "first": true,
    "last": true,
    "empty": true
  }
}
```

<h3 id="search-jobs-by-filter-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Job GET: OK|[GetAllEntityResponseBodyJob](#schemagetallentityresponsebodyjob)|

<aside class="success">
This operation does not require authentication
</aside>

<h1 id="house-tables-api-database">Database</h1>

## Get a Database identified by databaseId.

<a id="opIdgetDatabase"></a>

> Code samples

`GET /hts/databases`

Returns the database entity identified by databaseId, matched ignoring case.

<h3 id="get-a-database-identified-by-databaseid.-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|query|string|true|none|

> Example responses

> 200 Response

```json
{
  "entity": {
    "databaseUuid": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
    "databaseId": "my_database",
    "creationTime": 1651002318265,
    "properties": {
      "retention.days": "30"
    }
  }
}
```

<h3 id="get-a-database-identified-by-databaseid.-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Database GET: OK|[EntityResponseBodyDatabase](#schemaentityresponsebodydatabase)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Database GET: BAD_REQUEST|[EntityResponseBodyDatabase](#schemaentityresponsebodydatabase)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Database GET: DB_NOT_FOUND|[EntityResponseBodyDatabase](#schemaentityresponsebodydatabase)|

<aside class="success">
This operation does not require authentication
</aside>

## Create a Database

<a id="opIdcreateDatabase"></a>

> Code samples

`POST /hts/databases`

Creates a database entity identified by databaseId with a server-generated UUID. Fails if a database with the same name already exists, ignoring case.

> Body parameter

```json
{
  "entity": {
    "databaseId": "my_database",
    "properties": {
      "retention.days": "30"
    }
  }
}
```

<h3 id="create-a-database-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|body|body|[CreateUpdateEntityRequestBodyDatabase](#schemacreateupdateentityrequestbodydatabase)|true|none|

> Example responses

> 201 Response

```json
{
  "entity": {
    "databaseUuid": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
    "databaseId": "my_database",
    "creationTime": 1651002318265,
    "properties": {
      "retention.days": "30"
    }
  }
}
```

<h3 id="create-a-database-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|201|[Created](https://tools.ietf.org/html/rfc7231#section-6.3.2)|Database POST: CREATED|[EntityResponseBodyDatabase](#schemaentityresponsebodydatabase)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Database POST: BAD_REQUEST|[EntityResponseBodyDatabase](#schemaentityresponsebodydatabase)|
|409|[Conflict](https://tools.ietf.org/html/rfc7231#section-6.5.8)|Database POST: CONFLICT|[EntityResponseBodyDatabase](#schemaentityresponsebodydatabase)|

<aside class="success">
This operation does not require authentication
</aside>

## Delete a Database

<a id="opIddeleteDatabase"></a>

> Code samples

`DELETE /hts/databases`

Deletes the database entity identified by databaseId. User tables in the database are left untouched.

<h3 id="delete-a-database-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|query|string|true|none|

<h3 id="delete-a-database-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|204|[No Content](https://tools.ietf.org/html/rfc7231#section-6.3.5)|Database DELETE: NO_CONTENT|None|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Database DELETE: BAD_REQUEST|None|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Database DELETE: DB_NOT_FOUND|None|

<aside class="success">
This operation does not require authentication
</aside>

## Update properties of a Database

<a id="opIdupdateDatabaseProperties"></a>

> Code samples

`PATCH /hts/databases/properties`

Applies a sparse update to the properties of the database identified by databaseId: keys in updates are set, keys in removals are removed, other properties are not modified. A key present in both updates and removals is rejected.

> Body parameter

```json
{
  "updates": {
    "retention.days": "30"
  },
  "removals": [
    "owner"
  ]
}
```

<h3 id="update-properties-of-a-database-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|query|string|true|none|
|body|body|[UpdateDatabasePropertiesRequestBody](#schemaupdatedatabasepropertiesrequestbody)|true|none|

> Example responses

> 200 Response

```json
{
  "updated": [
    "string"
  ],
  "removed": [
    "string"
  ],
  "missing": [
    "string"
  ]
}
```

<h3 id="update-properties-of-a-database-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Database properties PATCH: UPDATED|[UpdateDatabasePropertiesResponseBody](#schemaupdatedatabasepropertiesresponsebody)|
|400|[Bad Request](https://tools.ietf.org/html/rfc7231#section-6.5.1)|Database properties PATCH: BAD_REQUEST|[UpdateDatabasePropertiesResponseBody](#schemaupdatedatabasepropertiesresponsebody)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Database properties PATCH: DB_NOT_FOUND|[UpdateDatabasePropertiesResponseBody](#schemaupdatedatabasepropertiesresponsebody)|
|409|[Conflict](https://tools.ietf.org/html/rfc7231#section-6.5.8)|Database properties PATCH: CONFLICT|[UpdateDatabasePropertiesResponseBody](#schemaupdatedatabasepropertiesresponsebody)|
|422|[Unprocessable Entity](https://tools.ietf.org/html/rfc2518#section-10.3)|Database properties PATCH: UNPROCESSABLE_ENTITY|[UpdateDatabasePropertiesResponseBody](#schemaupdatedatabasepropertiesresponsebody)|

<aside class="success">
This operation does not require authentication
</aside>

<h1 id="house-tables-api-togglestatus">ToggleStatus</h1>

## Get a toggle status applied to a table.

<a id="opIdgetTableToggleStatus"></a>

> Code samples

`GET /hts/togglestatuses`

Returns a toggle status of databaseID and tableId on a featureId

<h3 id="get-a-toggle-status-applied-to-a-table.-parameters">Parameters</h3>

|Name|In|Type|Required|Description|
|---|---|---|---|---|
|databaseId|query|string|true|none|
|tableId|query|string|true|none|
|featureId|query|string|true|none|

> Example responses

> 200 Response

```json
{
  "entity": {
    "status": "Active"
  }
}
```

<h3 id="get-a-toggle-status-applied-to-a-table.-responses">Responses</h3>

|Status|Meaning|Description|Schema|
|---|---|---|---|
|200|[OK](https://tools.ietf.org/html/rfc7231#section-6.3.1)|Toggle status GET: OK|[EntityResponseBodyToggleStatus](#schemaentityresponsebodytogglestatus)|
|404|[Not Found](https://tools.ietf.org/html/rfc7231#section-6.5.4)|Toggle status GET: NOT FOUND|[EntityResponseBodyToggleStatus](#schemaentityresponsebodytogglestatus)|

<aside class="success">
This operation does not require authentication
</aside>

# Schemas

<h2 id="tocS_CreateUpdateEntityRequestBodyUserTable">CreateUpdateEntityRequestBodyUserTable</h2>
<!-- backwards compatibility -->
<a id="schemacreateupdateentityrequestbodyusertable"></a>
<a id="schema_CreateUpdateEntityRequestBodyUserTable"></a>
<a id="tocScreateupdateentityrequestbodyusertable"></a>
<a id="tocscreateupdateentityrequestbodyusertable"></a>

```json
{
  "entity": {
    "deletedAtMs": 1751907524,
    "purgeAfterMs": 1751907524,
    "tableId": "my_table",
    "databaseId": "my_database",
    "tableVersion": "string",
    "metadataLocation": "string",
    "storageType": "hdfs",
    "creationTime": 1651002318265
  }
}

```

Request containing details of the User Table to be created/updated

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|entity|[UserTable](#schemausertable)|true|none|The entity object that clients want to create/update in the target house table.|

<h2 id="tocS_UserTable">UserTable</h2>
<!-- backwards compatibility -->
<a id="schemausertable"></a>
<a id="schema_UserTable"></a>
<a id="tocSusertable"></a>
<a id="tocsusertable"></a>

```json
{
  "deletedAtMs": 1751907524,
  "purgeAfterMs": 1751907524,
  "tableId": "my_table",
  "databaseId": "my_database",
  "tableVersion": "string",
  "metadataLocation": "string",
  "storageType": "hdfs",
  "creationTime": 1651002318265
}

```

The entity object that clients want to create/update in the target house table.

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|deletedAtMs|integer(int64)|false|none|Timestamp in milliseconds when the table was soft-deleted. If null, then the table is active|
|purgeAfterMs|integer(int64)|false|none|Unix timestamp in milliseconds indicating when the table will be deleted permanently by another process. If the current time is past the TTL, then the table could be arbitrarily deleted at any time.|
|tableId|string|true|none|Unique Resource identifier for a table within a Database.|
|databaseId|string|true|none|Unique Resource identifier for the Database containing the Table. Together with tableID they form a composite primary key for a user table.|
|tableVersion|string|false|none|Current Version of the user table. New record should have 'INTITAL_VERISON'|
|metadataLocation|string|true|none|Full URI for the file manifesting the newest version of a user table.|
|storageType|string|false|none|Storage type to be used for the table.|
|creationTime|integer(int64)|false|none|Creation time of the table.|

<h2 id="tocS_EntityResponseBodyUserTable">EntityResponseBodyUserTable</h2>
<!-- backwards compatibility -->
<a id="schemaentityresponsebodyusertable"></a>
<a id="schema_EntityResponseBodyUserTable"></a>
<a id="tocSentityresponsebodyusertable"></a>
<a id="tocsentityresponsebodyusertable"></a>

```json
{
  "entity": {
    "deletedAtMs": 1751907524,
    "purgeAfterMs": 1751907524,
    "tableId": "my_table",
    "databaseId": "my_database",
    "tableVersion": "string",
    "metadataLocation": "string",
    "storageType": "hdfs",
    "creationTime": 1651002318265
  }
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|entity|[UserTable](#schemausertable)|true|none|The entity object that clients want to create/update in the target house table.|

<h2 id="tocS_CreateUpdateEntityRequestBodyJob">CreateUpdateEntityRequestBodyJob</h2>
<!-- backwards compatibility -->
<a id="schemacreateupdateentityrequestbodyjob"></a>
<a id="schema_CreateUpdateEntityRequestBodyJob"></a>
<a id="tocScreateupdateentityrequestbodyjob"></a>
<a id="tocscreateupdateentityrequestbodyjob"></a>

```json
{
  "entity": {
    "jobId": "24efc962-9962-4522-b0b6-29490d7d8a0e",
    "state": "QUEUED",
    "version": "539482",
    "jobName": "my_job",
    "clusterId": "my_cluster",
    "creationTimeMs": 1651002318265,
    "startTimeMs": 1651002318265,
    "finishTimeMs": 1651002318265,
    "lastUpdateTimeMs": 1651002318265,
    "jobConf": "{'jobType': 'RETENTION', 'table': 'db.tb'}",
    "heartbeatTimeMs": 1651002318265,
    "executionId": "application_1642969576960_13278206",
    "engineType": "LIVY",
    "retentionTimeSec": 1745908497
  }
}

```

Request containing details of the User Table to be created/updated

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|entity|[Job](#schemajob)|true|none|The entity object that clients want to create/update in the target house table.|

<h2 id="tocS_Job">Job</h2>
<!-- backwards compatibility -->
<a id="schemajob"></a>
<a id="schema_Job"></a>
<a id="tocSjob"></a>
<a id="tocsjob"></a>

```json
{
  "jobId": "24efc962-9962-4522-b0b6-29490d7d8a0e",
  "state": "QUEUED",
  "version": "539482",
  "jobName": "my_job",
  "clusterId": "my_cluster",
  "creationTimeMs": 1651002318265,
  "startTimeMs": 1651002318265,
  "finishTimeMs": 1651002318265,
  "lastUpdateTimeMs": 1651002318265,
  "jobConf": "{'jobType': 'RETENTION', 'table': 'db.tb'}",
  "heartbeatTimeMs": 1651002318265,
  "executionId": "application_1642969576960_13278206",
  "engineType": "LIVY",
  "retentionTimeSec": 1745908497
}

```

The entity object that clients want to create/update in the target house table.

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|jobId|string|true|none|Unique Resource identifier for a job within a Database.|
|state|string|false|none|State for the job|
|version|string|false|none|Version of the job entry in HTS. HTS internally generates the next version after a successful PUT.The value would be a string representing a random integer.|
|jobName|string|true|none|Name of a job, doesn't need to be unique|
|clusterId|string|true|none|Unique identifier for the cluster|
|creationTimeMs|integer(int64)|false|none|Job creation time in unix epoch milliseconds|
|startTimeMs|integer(int64)|false|none|Job start time in unix epoch milliseconds|
|finishTimeMs|integer(int64)|false|none|Job finish time in unix epoch milliseconds|
|lastUpdateTimeMs|integer(int64)|false|none|Job contents last update time in unix epoch milliseconds|
|jobConf|string|false|none|Job config|
|heartbeatTimeMs|integer(int64)|false|none|Running job heartbeat timestamp in milliseconds|
|executionId|string|false|none|Launched job execution id specific to engine|
|engineType|string|false|none|Engine type that job submitted to|
|retentionTimeSec|integer(int64)|false|none|Job retention time in seconds. This is used to retain job based defined retention period|

<h2 id="tocS_EntityResponseBodyJob">EntityResponseBodyJob</h2>
<!-- backwards compatibility -->
<a id="schemaentityresponsebodyjob"></a>
<a id="schema_EntityResponseBodyJob"></a>
<a id="tocSentityresponsebodyjob"></a>
<a id="tocsentityresponsebodyjob"></a>

```json
{
  "entity": {
    "jobId": "24efc962-9962-4522-b0b6-29490d7d8a0e",
    "state": "QUEUED",
    "version": "539482",
    "jobName": "my_job",
    "clusterId": "my_cluster",
    "creationTimeMs": 1651002318265,
    "startTimeMs": 1651002318265,
    "finishTimeMs": 1651002318265,
    "lastUpdateTimeMs": 1651002318265,
    "jobConf": "{'jobType': 'RETENTION', 'table': 'db.tb'}",
    "heartbeatTimeMs": 1651002318265,
    "executionId": "application_1642969576960_13278206",
    "engineType": "LIVY",
    "retentionTimeSec": 1745908497
  }
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|entity|[Job](#schemajob)|true|none|The entity object that clients want to create/update in the target house table.|

<h2 id="tocS_CreateUpdateEntityRequestBodyDatabase">CreateUpdateEntityRequestBodyDatabase</h2>
<!-- backwards compatibility -->
<a id="schemacreateupdateentityrequestbodydatabase"></a>
<a id="schema_CreateUpdateEntityRequestBodyDatabase"></a>
<a id="tocScreateupdateentityrequestbodydatabase"></a>
<a id="tocscreateupdateentityrequestbodydatabase"></a>

```json
{
  "entity": {
    "databaseUuid": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
    "databaseId": "my_database",
    "creationTime": 1651002318265,
    "properties": {
      "retention.days": "30"
    }
  }
}

```

Request containing details of the Database to be created

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|entity|[Database](#schemadatabase)|true|none|The entity object that clients want to create/update in the target house table.|

<h2 id="tocS_Database">Database</h2>
<!-- backwards compatibility -->
<a id="schemadatabase"></a>
<a id="schema_Database"></a>
<a id="tocSdatabase"></a>
<a id="tocsdatabase"></a>

```json
{
  "databaseUuid": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
  "databaseId": "my_database",
  "creationTime": 1651002318265,
  "properties": {
    "retention.days": "30"
  }
}

```

The entity object that clients want to create/update in the target house table.

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|databaseUuid|string|false|read-only|Server-generated UUID that is the canonical identity of the database. Read-only.|
|databaseId|string|true|none|Logical name of the database. Unique ignoring case.|
|creationTime|integer(int64)|false|read-only|Creation time of the database in epoch milliseconds. Read-only.|
|properties|object|false|none|Key-value properties of the database.|
|» **additionalProperties**|string|false|none|Key-value properties of the database.|

<h2 id="tocS_EntityResponseBodyDatabase">EntityResponseBodyDatabase</h2>
<!-- backwards compatibility -->
<a id="schemaentityresponsebodydatabase"></a>
<a id="schema_EntityResponseBodyDatabase"></a>
<a id="tocSentityresponsebodydatabase"></a>
<a id="tocsentityresponsebodydatabase"></a>

```json
{
  "entity": {
    "databaseUuid": "3f1c1a4e-8e3f-4d0a-9c6c-0c3b2a5e7d11",
    "databaseId": "my_database",
    "creationTime": 1651002318265,
    "properties": {
      "retention.days": "30"
    }
  }
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|entity|[Database](#schemadatabase)|true|none|The entity object that clients want to create/update in the target house table.|

<h2 id="tocS_UpdateDatabasePropertiesRequestBody">UpdateDatabasePropertiesRequestBody</h2>
<!-- backwards compatibility -->
<a id="schemaupdatedatabasepropertiesrequestbody"></a>
<a id="schema_UpdateDatabasePropertiesRequestBody"></a>
<a id="tocSupdatedatabasepropertiesrequestbody"></a>
<a id="tocsupdatedatabasepropertiesrequestbody"></a>

```json
{
  "updates": {
    "retention.days": "30"
  },
  "removals": [
    "owner"
  ]
}

```

Property keys to set and property keys to remove

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|updates|object|false|none|Properties to set on the database. Existing keys are overwritten.|
|» **additionalProperties**|string|false|none|Properties to set on the database. Existing keys are overwritten.|
|removals|[string]|false|none|Property keys to remove from the database.|

<h2 id="tocS_UpdateDatabasePropertiesResponseBody">UpdateDatabasePropertiesResponseBody</h2>
<!-- backwards compatibility -->
<a id="schemaupdatedatabasepropertiesresponsebody"></a>
<a id="schema_UpdateDatabasePropertiesResponseBody"></a>
<a id="tocSupdatedatabasepropertiesresponsebody"></a>
<a id="tocsupdatedatabasepropertiesresponsebody"></a>

```json
{
  "updated": [
    "string"
  ],
  "removed": [
    "string"
  ],
  "missing": [
    "string"
  ]
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|updated|[string]|true|read-only|Property keys that were set.|
|removed|[string]|true|read-only|Property keys that were removed.|
|missing|[string]|false|read-only|Property keys requested for removal that were not present on the database.|

<h2 id="tocS_GetAllEntityResponseBodyUserTable">GetAllEntityResponseBodyUserTable</h2>
<!-- backwards compatibility -->
<a id="schemagetallentityresponsebodyusertable"></a>
<a id="schema_GetAllEntityResponseBodyUserTable"></a>
<a id="tocSgetallentityresponsebodyusertable"></a>
<a id="tocsgetallentityresponsebodyusertable"></a>

```json
{
  "results": [
    {
      "deletedAtMs": 1751907524,
      "purgeAfterMs": 1751907524,
      "tableId": "my_table",
      "databaseId": "my_database",
      "tableVersion": "string",
      "metadataLocation": "string",
      "storageType": "hdfs",
      "creationTime": 1651002318265
    }
  ],
  "pageResults": {
    "totalPages": 0,
    "totalElements": 0,
    "size": 0,
    "content": [
      {
        "deletedAtMs": 1751907524,
        "purgeAfterMs": 1751907524,
        "tableId": "my_table",
        "databaseId": "my_database",
        "tableVersion": "string",
        "metadataLocation": "string",
        "storageType": "hdfs",
        "creationTime": 1651002318265
      }
    ],
    "number": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "numberOfElements": 0,
    "pageable": {
      "offset": 0,
      "sort": {
        "empty": true,
        "sorted": true,
        "unsorted": true
      },
      "pageNumber": 0,
      "pageSize": 0,
      "paged": true,
      "unpaged": true
    },
    "first": true,
    "last": true,
    "empty": true
  }
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|results|[[UserTable](#schemausertable)]|false|read-only|List of user table objects in House table|
|pageResults|[PageUserTable](#schemapageusertable)|false|none|Page of user table objects in House table|

<h2 id="tocS_PageUserTable">PageUserTable</h2>
<!-- backwards compatibility -->
<a id="schemapageusertable"></a>
<a id="schema_PageUserTable"></a>
<a id="tocSpageusertable"></a>
<a id="tocspageusertable"></a>

```json
{
  "totalPages": 0,
  "totalElements": 0,
  "size": 0,
  "content": [
    {
      "deletedAtMs": 1751907524,
      "purgeAfterMs": 1751907524,
      "tableId": "my_table",
      "databaseId": "my_database",
      "tableVersion": "string",
      "metadataLocation": "string",
      "storageType": "hdfs",
      "creationTime": 1651002318265
    }
  ],
  "number": 0,
  "sort": {
    "empty": true,
    "sorted": true,
    "unsorted": true
  },
  "numberOfElements": 0,
  "pageable": {
    "offset": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "pageNumber": 0,
    "pageSize": 0,
    "paged": true,
    "unpaged": true
  },
  "first": true,
  "last": true,
  "empty": true
}

```

Page of user table objects in House table

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|totalPages|integer(int32)|false|none|none|
|totalElements|integer(int64)|false|none|none|
|size|integer(int32)|false|none|none|
|content|[[UserTable](#schemausertable)]|false|none|[The entity object that clients want to create/update in the target house table.]|
|number|integer(int32)|false|none|none|
|sort|[SortObject](#schemasortobject)|false|none|none|
|numberOfElements|integer(int32)|false|none|none|
|pageable|[PageableObject](#schemapageableobject)|false|none|none|
|first|boolean|false|none|none|
|last|boolean|false|none|none|
|empty|boolean|false|none|none|

<h2 id="tocS_PageableObject">PageableObject</h2>
<!-- backwards compatibility -->
<a id="schemapageableobject"></a>
<a id="schema_PageableObject"></a>
<a id="tocSpageableobject"></a>
<a id="tocspageableobject"></a>

```json
{
  "offset": 0,
  "sort": {
    "empty": true,
    "sorted": true,
    "unsorted": true
  },
  "pageNumber": 0,
  "pageSize": 0,
  "paged": true,
  "unpaged": true
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|offset|integer(int64)|false|none|none|
|sort|[SortObject](#schemasortobject)|false|none|none|
|pageNumber|integer(int32)|false|none|none|
|pageSize|integer(int32)|false|none|none|
|paged|boolean|false|none|none|
|unpaged|boolean|false|none|none|

<h2 id="tocS_SortObject">SortObject</h2>
<!-- backwards compatibility -->
<a id="schemasortobject"></a>
<a id="schema_SortObject"></a>
<a id="tocSsortobject"></a>
<a id="tocssortobject"></a>

```json
{
  "empty": true,
  "sorted": true,
  "unsorted": true
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|empty|boolean|false|none|none|
|sorted|boolean|false|none|none|
|unsorted|boolean|false|none|none|

<h2 id="tocS_EntityResponseBodyToggleStatus">EntityResponseBodyToggleStatus</h2>
<!-- backwards compatibility -->
<a id="schemaentityresponsebodytogglestatus"></a>
<a id="schema_EntityResponseBodyToggleStatus"></a>
<a id="tocSentityresponsebodytogglestatus"></a>
<a id="tocsentityresponsebodytogglestatus"></a>

```json
{
  "entity": {
    "status": "Active"
  }
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|entity|[ToggleStatus](#schematogglestatus)|true|none|Representing a row in the house table|

<h2 id="tocS_ToggleStatus">ToggleStatus</h2>
<!-- backwards compatibility -->
<a id="schematogglestatus"></a>
<a id="schema_ToggleStatus"></a>
<a id="tocStogglestatus"></a>
<a id="tocstogglestatus"></a>

```json
{
  "status": "Active"
}

```

Representing a row in the house table 

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|status|string|true|none|Status of an entity with respect to whether a feature has been toggled on|

#### Enumerated Values

|Property|Value|
|---|---|
|status|ACTIVE|
|status|INACTIVE|

<h2 id="tocS_GetAllEntityResponseBodyJob">GetAllEntityResponseBodyJob</h2>
<!-- backwards compatibility -->
<a id="schemagetallentityresponsebodyjob"></a>
<a id="schema_GetAllEntityResponseBodyJob"></a>
<a id="tocSgetallentityresponsebodyjob"></a>
<a id="tocsgetallentityresponsebodyjob"></a>

```json
{
  "results": [
    {
      "jobId": "24efc962-9962-4522-b0b6-29490d7d8a0e",
      "state": "QUEUED",
      "version": "539482",
      "jobName": "my_job",
      "clusterId": "my_cluster",
      "creationTimeMs": 1651002318265,
      "startTimeMs": 1651002318265,
      "finishTimeMs": 1651002318265,
      "lastUpdateTimeMs": 1651002318265,
      "jobConf": "{'jobType': 'RETENTION', 'table': 'db.tb'}",
      "heartbeatTimeMs": 1651002318265,
      "executionId": "application_1642969576960_13278206",
      "engineType": "LIVY",
      "retentionTimeSec": 1745908497
    }
  ],
  "pageResults": {
    "totalPages": 0,
    "totalElements": 0,
    "size": 0,
    "content": [
      {
        "jobId": "24efc962-9962-4522-b0b6-29490d7d8a0e",
        "state": "QUEUED",
        "version": "539482",
        "jobName": "my_job",
        "clusterId": "my_cluster",
        "creationTimeMs": 1651002318265,
        "startTimeMs": 1651002318265,
        "finishTimeMs": 1651002318265,
        "lastUpdateTimeMs": 1651002318265,
        "jobConf": "{'jobType': 'RETENTION', 'table': 'db.tb'}",
        "heartbeatTimeMs": 1651002318265,
        "executionId": "application_1642969576960_13278206",
        "engineType": "LIVY",
        "retentionTimeSec": 1745908497
      }
    ],
    "number": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "numberOfElements": 0,
    "pageable": {
      "offset": 0,
      "sort": {
        "empty": true,
        "sorted": true,
        "unsorted": true
      },
      "pageNumber": 0,
      "pageSize": 0,
      "paged": true,
      "unpaged": true
    },
    "first": true,
    "last": true,
    "empty": true
  }
}

```

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|results|[[Job](#schemajob)]|false|read-only|List of user table objects in House table|
|pageResults|[PageJob](#schemapagejob)|false|none|Page of user table objects in House table|

<h2 id="tocS_PageJob">PageJob</h2>
<!-- backwards compatibility -->
<a id="schemapagejob"></a>
<a id="schema_PageJob"></a>
<a id="tocSpagejob"></a>
<a id="tocspagejob"></a>

```json
{
  "totalPages": 0,
  "totalElements": 0,
  "size": 0,
  "content": [
    {
      "jobId": "24efc962-9962-4522-b0b6-29490d7d8a0e",
      "state": "QUEUED",
      "version": "539482",
      "jobName": "my_job",
      "clusterId": "my_cluster",
      "creationTimeMs": 1651002318265,
      "startTimeMs": 1651002318265,
      "finishTimeMs": 1651002318265,
      "lastUpdateTimeMs": 1651002318265,
      "jobConf": "{'jobType': 'RETENTION', 'table': 'db.tb'}",
      "heartbeatTimeMs": 1651002318265,
      "executionId": "application_1642969576960_13278206",
      "engineType": "LIVY",
      "retentionTimeSec": 1745908497
    }
  ],
  "number": 0,
  "sort": {
    "empty": true,
    "sorted": true,
    "unsorted": true
  },
  "numberOfElements": 0,
  "pageable": {
    "offset": 0,
    "sort": {
      "empty": true,
      "sorted": true,
      "unsorted": true
    },
    "pageNumber": 0,
    "pageSize": 0,
    "paged": true,
    "unpaged": true
  },
  "first": true,
  "last": true,
  "empty": true
}

```

Page of user table objects in House table

### Properties

|Name|Type|Required|Restrictions|Description|
|---|---|---|---|---|
|totalPages|integer(int32)|false|none|none|
|totalElements|integer(int64)|false|none|none|
|size|integer(int32)|false|none|none|
|content|[[Job](#schemajob)]|false|none|[The entity object that clients want to create/update in the target house table.]|
|number|integer(int32)|false|none|none|
|sort|[SortObject](#schemasortobject)|false|none|none|
|numberOfElements|integer(int32)|false|none|none|
|pageable|[PageableObject](#schemapageableobject)|false|none|none|
|first|boolean|false|none|none|
|last|boolean|false|none|none|
|empty|boolean|false|none|none|

