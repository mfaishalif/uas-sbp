import styles from './Sections.module.css';

const steps = [
  {
    name: 'Retrieve',
    title: 'Find the three closest cases',
    body: 'Your complaint is scored against every case in the library, CFPB and analyst-saved alike. The three highest total scores above the threshold come back.',
  },
  {
    name: 'Reuse',
    title: 'See how they were resolved',
    body: 'Each precedent shows the response its company gave. Those responses are tallied into a similarity-weighted recommendation.',
  },
  {
    name: 'Revise',
    title: 'Choose the response that fits',
    body: 'Keep the recommended response or pick another, then write handling notes that explain your decision.',
  },
  {
    name: 'Retain',
    title: 'Save it to the case library',
    body: 'The complaint is stored as an Analyst case with the precedents as references, so the next search can learn from it.',
  },
];

export function HowItWorks() {
  return (
    <section id="how-it-works" className={styles.contentBand} aria-labelledby="how-title">
      <div className="fin-container">
        <div className={styles.heading}>
          <p className="page-eyebrow">The case-based reasoning cycle</p>
          <h2 className={styles.title} id="how-title">How it works</h2>
          <p className={styles.lead}>
            FinResolve follows the four steps of case-based reasoning, all on this page.
          </p>
        </div>
        <ol className={styles.steps}>
          {steps.map((step, index) => (
            <li key={step.name} className={styles.step}>
              <span className={styles.stepNumber} aria-hidden="true">{index + 1}</span>
              <p className={styles.stepName}>{step.name}</p>
              <h3 className={styles.stepTitle}>{step.title}</h3>
              <p className={styles.stepBody}>{step.body}</p>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}
