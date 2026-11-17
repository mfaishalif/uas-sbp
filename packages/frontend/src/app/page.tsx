import { ClosingCta } from '@/components/home/ClosingCta';
import { DataSource } from '@/components/home/DataSource';
import { Faq } from '@/components/home/Faq';
import { HowItWorks } from '@/components/home/HowItWorks';
import { PrecedentWorkspace } from '@/components/home/PrecedentWorkspace';
import { ScoringExplainer } from '@/components/home/ScoringExplainer';

export default function HomePage() {
  return (
    <>
      <PrecedentWorkspace />
      <HowItWorks />
      <ScoringExplainer />
      <DataSource />
      <Faq />
      <ClosingCta />
    </>
  );
}
