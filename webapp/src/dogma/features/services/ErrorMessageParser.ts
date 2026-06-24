const UNKNOWN_ERROR_MESSAGE = 'An unknown error occurred.';

class ErrorMessageParser {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  static parse(object: any): string {
    if (object == null) {
      return UNKNOWN_ERROR_MESSAGE;
    }
    if (typeof object === 'string') {
      return object;
    }
    if (object.response && object.response.data && object.response.data.message) {
      return ErrorMessageParser.asString(object.response.data.message);
    }
    if (object.error) {
      // 'error' may be a string or a nested error-like object/array; re-parse non-strings so the returned
      // value is always a string.
      return ErrorMessageParser.asString(object.error);
    }
    if (object.data && object.data.message) {
      let message = ErrorMessageParser.asString(object.data.message);
      if (object.data.detail) {
        message += '\n' + ErrorMessageParser.asString(object.data.detail);
      }
      return message;
    }
    if (object.message) {
      return ErrorMessageParser.asString(object.message);
    }
    // Fall back to a serialized form (e.g. an RTK Query FetchBaseQueryError whose body is not an
    // object with a 'message') so that callers always show a meaningful, non-empty error description
    // instead of a blank one.
    try {
      const json = JSON.stringify(object);
      if (json && json !== '{}') {
        return json;
      }
    } catch {
      // Ignore non-serializable values (e.g. circular references) and use the generic message below.
    }
    return UNKNOWN_ERROR_MESSAGE;
  }

  // Ensures a string is returned: a string passes through, anything else is re-parsed so callers (e.g.
  // Deferred and auth rendering) always receive a string rather than an object or array.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private static asString(value: any): string {
    return typeof value === 'string' ? value : ErrorMessageParser.parse(value);
  }
}

export default ErrorMessageParser;
