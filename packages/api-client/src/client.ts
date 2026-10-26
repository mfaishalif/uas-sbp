import {
    CasesApi,
    CategoriesApi,
    Configuration,
    ImportsApi,
    SearchApi,
    SystemApi,
    type ApiError,
    ApiErrorFromJSON,
    type FetchAPI,
    type FieldError,
    type Middleware,
} from './generated/index.js';

/**
 * One safe client-side error shape.
 *
 * <p>{@code status} 0 plus {@code TRANSPORT_ERROR} means no HTTP response was received or the
 * failure body was not a generated {@link ApiError}. Mutations are never retried implicitly.
 */
export class ApiClientError extends Error {
    override name: 'ApiClientError' = 'ApiClientError';

    constructor(
        public readonly status: number,
        public readonly code: string,
        message: string,
        public readonly fieldErrors: FieldError[] = [],
        options?: ErrorOptions,
    ) {
        super(message, options);
    }
}

export interface FinResolveClient {
    readonly configuration: Configuration;
    readonly system: SystemApi;
    readonly categories: CategoriesApi;
    readonly cases: CasesApi;
    readonly search: SearchApi;
    readonly imports: ImportsApi;
}

/**
 * Build the generated tag APIs over one base URL and one fetch implementation.
 *
 * <p>{@code baseUrl} may be the server root or already end in {@code /api}. A non-2xx response is
 * converted to {@link ApiClientError} before generated deserialization sees it.
 */
export function createClient(
    baseUrl: string,
    fetchApi: FetchAPI = globalThis.fetch,
): FinResolveClient {
    const trimmed = baseUrl.replace(/\/+$/, '');
    const basePath = trimmed.endsWith('/api') ? trimmed : `${trimmed}/api`;

    const wrappedFetch: FetchAPI = async (input, init) => {
        let response: Response;
        try {
            response = await fetchApi(input, init);
        } catch (cause) {
            throw new ApiClientError(
                0,
                'TRANSPORT_ERROR',
                'Tidak dapat menghubungi server.',
                [],
                { cause },
            );
        }

        if (response.ok) {
            return response;
        }

        let error: ApiError | undefined;
        try {
            error = ApiErrorFromJSON(await response.clone().json());
        } catch {
            error = undefined;
        }
        if (error && typeof error.code === 'string') {
            throw new ApiClientError(
                response.status,
                error.code,
                error.message,
                error.fieldErrors ?? [],
            );
        }
        throw new ApiClientError(
            response.status,
            'TRANSPORT_ERROR',
            'Respons kesalahan tidak dapat dibaca.',
            [],
        );
    };

    const passthroughErrors: Middleware = {
        onError: ({ error }) => {
            // The generated runtime otherwise wraps every fetch failure in FetchError.
            throw error;
        },
    };
    const configuration = new Configuration({
        basePath,
        fetchApi: wrappedFetch,
        middleware: [passthroughErrors],
    });
    return {
        configuration,
        system: new SystemApi(configuration),
        categories: new CategoriesApi(configuration),
        cases: new CasesApi(configuration),
        search: new SearchApi(configuration),
        imports: new ImportsApi(configuration),
    };
}
