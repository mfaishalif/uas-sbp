import styles from './Sections.module.css';

export const CFPB_DATABASE_URL = 'https://www.consumerfinance.gov/data-research/consumer-complaints/';
const CFPB_ARCHIVE_URL =
  'https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip';

/** Counts of "Company response to consumer" in data/cfpb/sample.csv. */
const RESPONSE_MIX = [
  { response: 'Closed with explanation', count: 391 },
  { response: 'Closed with monetary relief', count: 73 },
  { response: 'Closed with non-monetary relief', count: 36 },
];

export function DataSource() {
  return (
    <section id="data-source" className={styles.contentBand} aria-labelledby="data-title">
      <div className={`fin-container ${styles.split}`}>
        <div className={styles.heading}>
          <p className="page-eyebrow">Data source</p>
          <h2 className={styles.title} id="data-title">CFPB Consumer Complaint Database</h2>
          <p className={styles.lead}>
            The case library starts with 500 real complaints published by the U.S.
            Consumer Financial Protection Bureau. The CFPB publishes narratives only
            with the consumer’s consent, removes personal information (you will see
            it as XX), and does not verify every fact a complaint alleges.
          </p>
          <p className={styles.links}>
            <a href={CFPB_DATABASE_URL} target="_blank" rel="noreferrer noopener">
              About the CFPB Consumer Complaint Database
              <span className="visually-hidden"> (opens in a new tab)</span>
            </a>
            <a href={CFPB_ARCHIVE_URL} target="_blank" rel="noreferrer noopener">
              Source archive, Nov–Dec 2024 (ZIP)
              <span className="visually-hidden"> (opens in a new tab)</span>
            </a>
          </p>
        </div>

        <dl className={styles.facts}>
          <div className={styles.fact}>
            <dt>Complaints</dt>
            <dd>500 with a narrative</dd>
          </div>
          <div className={styles.fact}>
            <dt>Received</dt>
            <dd>1 Nov – 31 Dec 2024</dd>
          </div>
          <div className={styles.fact}>
            <dt>Products</dt>
            <dd>Checking or savings account; Money transfer, virtual currency, or money service</dd>
          </div>
          <div className={styles.fact}>
            <dt>Responses in the sample</dt>
            <dd>
              <ul className={styles.mix}>
                {RESPONSE_MIX.map((item) => (
                  <li key={item.response}>
                    <span>{item.response}</span>
                    <strong>{item.count}</strong>
                  </li>
                ))}
              </ul>
            </dd>
          </div>
        </dl>
      </div>
    </section>
  );
}
