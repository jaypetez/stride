import { errorMessage } from '../api';

/** A failed request's message (the API's `{ error }` when it answered). */
export function ErrorText({ error, fallback }: { error: unknown; fallback?: string }) {
  return (
    <p className="error" role="status">
      {errorMessage(error, fallback)}
    </p>
  );
}
