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
      return object.response.data.message;
    }
    if (object.error) {
      // 'error' may be a string or a nested error-like object/array; re-parse non-strings so the returned
      // value is always a string.
      return typeof object.error === 'string' ? object.error : ErrorMessageParser.parse(object.error);
    }
    if (object.data && object.data.message) {
      let message = object.data.message;
      if (object.data.detail) {
        message += '\n' + object.data.detail;
      }
      return message;
    }
    if (object.message) {
      return object.message;
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
}

export default ErrorMessageParser;
