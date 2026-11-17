import styles from './Sections.module.css';

const questions = [
  {
    question: 'Where does the data come from?',
    answer: 'From the CFPB Consumer Complaint Database: 500 complaints with narratives received between November and December 2024. They load automatically when the API starts. Cases you save are added to the same library.',
  },
  {
    question: 'What does a score of, say, 72% mean?',
    answer: 'It is how similar a past case is to your complaint, combining category agreement and shared vocabulary. It is not the probability that a response will succeed, and it has not been calibrated against human judgement.',
  },
  {
    question: 'How is the recommended response calculated?',
    answer: 'Each precedent shown votes for its company response, weighted by its total score. A response’s share is the sum of its precedents’ scores divided by the sum of all shown scores. The largest share is recommended; a tie goes to the response of the highest-scoring precedent.',
  },
  {
    question: 'Why can a search return nothing?',
    answer: 'A case must share at least one meaningful word with your narrative and reach the minimum total score. Very short narratives, or ones made mostly of stopwords and numbers, often fall short. Add the concrete facts and search again.',
  },
  {
    question: 'What happens when I save a case?',
    answer: 'It is stored as an Analyst case with your categories, narrative, chosen response, handling notes, and the precedents as references. Its outcome is recorded as unknown. It appears in the case library immediately and becomes a candidate in the next search.',
  },
];

export function Faq() {
  return (
    <section id="faq" className={styles.sageBand} aria-labelledby="faq-title">
      <div className={`fin-container ${styles.faqLayout}`}>
        <div className={styles.heading}>
          <p className="page-eyebrow">Questions</p>
          <h2 className={styles.title} id="faq-title">FAQ</h2>
        </div>
        <div className={styles.faqList}>
          {questions.map((item) => (
            <details key={item.question} className={styles.faqItem}>
              <summary>{item.question}</summary>
              <p>{item.answer}</p>
            </details>
          ))}
        </div>
      </div>
    </section>
  );
}
