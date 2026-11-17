import type { SearchConfiguration } from '@finresolve/api-client';
import { formatScore, formatWeight } from '@/lib/format';
import { barWidth, scoreBreakdown } from '@/lib/scoring';
import styles from './ScoreBar.module.css';

/**
 * Stacked bar of the total score: category contribution then text contribution,
 * on a 0–100% track with the inclusion threshold marked.
 */
export function ScoreBar({
  categoryScore,
  textScore,
  configuration,
}: {
  categoryScore: number;
  textScore: number;
  configuration: SearchConfiguration;
}) {
  const parts = scoreBreakdown({ categoryScore, textScore }, configuration);
  const label = `Category contributes ${formatScore(parts.category)}, text contributes ${formatScore(parts.text)}. Threshold ${formatScore(configuration.threshold)}.`;

  return (
    <div className={styles.wrap}>
      <div className={styles.track} role="img" aria-label={label}>
        <span className={styles.category} style={{ width: barWidth(parts.category) }} />
        <span className={styles.text} style={{ width: barWidth(parts.text) }} />
        <span
          className={styles.threshold}
          style={{ left: barWidth(configuration.threshold) }}
          aria-hidden="true"
        />
      </div>
      <dl className={styles.legend}>
        <div>
          <dt>
            <span className={`${styles.swatch} ${styles.category}`} aria-hidden="true" />
            Category
          </dt>
          <dd>
            {formatScore(categoryScore)} × {formatWeight(configuration.categoryWeight)}
            {' = '}
            <strong>{formatScore(parts.category)}</strong>
          </dd>
        </div>
        <div>
          <dt>
            <span className={`${styles.swatch} ${styles.text}`} aria-hidden="true" />
            Text
          </dt>
          <dd>
            {formatScore(textScore)} × {formatWeight(configuration.textWeight)}
            {' = '}
            <strong>{formatScore(parts.text)}</strong>
          </dd>
        </div>
      </dl>
    </div>
  );
}
