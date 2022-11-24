class ErrorHandler {
  static handle(error: any): string {
    if (error.response && error.response.data.message) {
      return error.response.data.message;
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
