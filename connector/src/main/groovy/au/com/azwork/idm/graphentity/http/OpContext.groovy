package au.com.azwork.idm.graphentity.http

/** Which ICF operation produced an HTTP response; drives the §13 exception mapping. */
enum OpContext {
    TEST, GET, SEARCH, CREATE, UPDATE, DELETE
}
