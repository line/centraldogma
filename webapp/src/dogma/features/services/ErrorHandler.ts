class ErrorHandler {
  // eslint-disable-line @typescript-eslint/no-explicit-any
  static handle(error: any): string {
    if (error.response && error.response.data.message) {
      return error.response.data.message;
    }
    if (error.data && error.data.message) {
      return error.data.message;
    }
    if (error.message) {
      return error.message;
    }
    if (typeof error === 'string') {
      return error;
    }
    return 'Uncaught Error';
  }
}

export default ErrorHandler;
