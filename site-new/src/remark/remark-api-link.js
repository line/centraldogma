import apiIndex from '../../gen-src/api-index.json';
import { createRemarkApiLink } from './remark-api-link-transform.mjs';

/**
 * Turns `[CentralDogma](type)` into a link backed by the generated Javadoc index.
 */
export default createRemarkApiLink(apiIndex);
