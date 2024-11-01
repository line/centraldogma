class ErrorMessageParser {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  static parse(object: any): string {
    if (object.response && object.response.data.message) {
      return object.response.data.message;
    }
    if (object.error) {
      return object.error;
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
    if (typeof object === 'string') {
      return object;
    }
    return null;
  }
}

export default ErrorMessageParser;
