import { LoadingState } from '@/components/ui/LoadingState';

export default function Loading() {
  return (
    <div className="fin-container fin-page">
      <LoadingState label="Loading FinResolve…" />
    </div>
  );
}
