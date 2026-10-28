import { LoadingState } from '@/components/ui/LoadingState';

export default function Loading() {
  return (
    <div className="fin-container" style={{ paddingBlock: '48px' }}>
      <LoadingState label="Loading FinResolve…" />
    </div>
  );
}
