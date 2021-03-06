/*
 * Copyright 2016 Lukáš Petrovický
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.triceo.robozonky.strategy.simple;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.triceo.robozonky.PortfolioOverview;
import com.github.triceo.robozonky.remote.Loan;
import com.github.triceo.robozonky.remote.Rating;
import com.github.triceo.robozonky.strategy.InvestmentStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SimpleInvestmentStrategy implements InvestmentStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleInvestmentStrategy.class);
    private static final Comparator<Loan> BY_TERM =
            (l1, l2) -> Integer.compare(l1.getTermInMonths(), l2.getTermInMonths());

    static Map<Rating, Collection<Loan>> sortLoansByRating(final Collection<Loan> loans) {
        final Map<Rating, Collection<Loan>> result = new EnumMap<>(Rating.class);
        loans.forEach(l -> result.compute(l.getRating(), (k, v) -> {
            final Collection<Loan> values = (v == null) ? new LinkedHashSet<>(loans.size() / 2) : v;
            values.add(l);
            return values;
        }));
        return Collections.unmodifiableMap(result);
    }

    private List<Rating> rankRatingsByDemand(final Map<Rating, BigDecimal> currentShare,
                                             final Function<StrategyPerRating, BigDecimal> metric) {
        final SortedMap<BigDecimal, List<Rating>> mostWantedRatings = new TreeMap<>(Comparator.reverseOrder());
        // put the ratings into buckets based on how much we're missing them
        currentShare.forEach((r, currentRatingShare) -> {
            final BigDecimal maximumAllowedShare = metric.apply(this.individualStrategies.get(r));
            final BigDecimal undershare = maximumAllowedShare.subtract(currentRatingShare);
            if (undershare.compareTo(BigDecimal.ZERO) <= 0) { // we over-invested into this rating; do not include
                return;
            }
            mostWantedRatings.compute(undershare, (k, v) -> { // each rating unique at source, so list works
                final List<Rating> target = (v == null) ? new ArrayList<>(1) : v;
                target.add(r);
                return target;
            });
        });
        return mostWantedRatings.entrySet().stream()
                .map(Map.Entry::getValue)
                .reduce(new ArrayList<>(Rating.values().length), (a, b) -> {
                    a.addAll(b);
                    return a;
                });
    }

    /**
     * Ranks the ratings in the portfolio which need to have their overall share increased. First on this list will be
     * ratings which are under their target share. Following them will be ratings which are under their maximum share.
     * Ratings with their respective shares over the maximum will not be present.
     *
     * @param currentShare Current share of investments in a given rating.
     * @return Ratings in the order of decreasing demand.
     */
    List<Rating> rankRatingsByDemand(final Map<Rating, BigDecimal> currentShare) {
        final List<Rating> ratingsUnderTarget = rankRatingsByDemand(currentShare, StrategyPerRating::getTargetShare);
        final List<Rating> ratingsUnderMaximum = rankRatingsByDemand(currentShare, StrategyPerRating::getMaximumShare);
        Collections.reverse(ratingsUnderMaximum); // the closer we get to maximum share, the less desirable
        final List<Rating> result = Stream.concat(ratingsUnderTarget.stream(), ratingsUnderMaximum.stream())
                .distinct()
                .collect(Collectors.toList());
        return Collections.unmodifiableList(result);
    }

    private final int minimumBalance, investmentCeiling;
    private final Map<Rating, StrategyPerRating> individualStrategies = new EnumMap<>(Rating.class);

    SimpleInvestmentStrategy(final int minimumBalance, final int investmentCeiling,
                             final Map<Rating, StrategyPerRating> individualStrategies) {
        this.minimumBalance = minimumBalance;
        this.investmentCeiling = investmentCeiling;
        for (final Rating r: Rating.values()) {
            if (!individualStrategies.containsKey(r)) {
                throw new IllegalArgumentException("Missing strategy for rating " + r);
            }
            final StrategyPerRating s = individualStrategies.get(r);
            this.individualStrategies.put(r, s);
        }
    }

    private boolean isAcceptable(final PortfolioOverview portfolio) {
        final int availableBalance = portfolio.getCzkAvailable();
        if (availableBalance < this.minimumBalance) {
            SimpleInvestmentStrategy.LOGGER.debug("According to the investment strategy, {} CZK balance is less than "
                    + "minimum {} CZK. Not recommending any loans.", availableBalance, this.minimumBalance);
            return false;
        }
        final int invested = portfolio.getCzkInvested();
        if (invested > this.investmentCeiling) {
            SimpleInvestmentStrategy.LOGGER.debug("According to the investment strategy, {} CZK total investment "
                    + "exceeds {} CZK ceiling. Not recommending any loans.", invested, this.investmentCeiling);
            return false;
        }
        return true;
    }

    @Override
    public List<Loan> getMatchingLoans(final List<Loan> availableLoans, final PortfolioOverview portfolio) {
        if (!this.isAcceptable(portfolio)) {
            return Collections.emptyList();
        }
        final List<Rating> mostWantedRatings = this.rankRatingsByDemand(portfolio.getSharesOnInvestment());
        SimpleInvestmentStrategy.LOGGER.info("According to the investment strategy, the portfolio is low "
                + "on following ratings: {}. The most under-invested come first.", mostWantedRatings);
        final Map<Rating, Collection<Loan>> splitByRating = SimpleInvestmentStrategy.sortLoansByRating(availableLoans);
        final List<Loan> acceptableLoans = new ArrayList<>(availableLoans.size());
        mostWantedRatings.forEach(rating -> {
            final Collection<Loan> loans = splitByRating.get(rating);
            if (loans == null || loans.isEmpty()) { // no loans of this rating
                return;
            }
            final Comparator<Loan> properOrder = this.individualStrategies.get(rating).isPreferLongerTerms() ?
                    SimpleInvestmentStrategy.BY_TERM.reversed() : SimpleInvestmentStrategy.BY_TERM;
            final Collection<Loan> acceptable = loans.stream().sorted(properOrder)
                    .filter(l -> this.individualStrategies.get(rating).isAcceptable(l))
                    .collect(Collectors.toList());
            acceptableLoans.addAll(acceptable);
        });
        return Collections.unmodifiableList(acceptableLoans);
    }

    @Override
    public int recommendInvestmentAmount(final Loan loan, final PortfolioOverview portfolio) {
        if (!this.isAcceptable(portfolio)) {
            return 0;
        }
        final Optional<int[]> recommend = individualStrategies.get(loan.getRating()).recommendInvestmentAmount(loan);
        if (!recommend.isPresent()) { // does not match the strategy
            return 0;
        }
        final int minimumRecommendation = recommend.get()[0];
        final int maximumRecommendation = recommend.get()[1];
        SimpleInvestmentStrategy.LOGGER.debug("Recommended investment range of <{}; {}> CZK.", minimumRecommendation,
                maximumRecommendation);
        // round to nearest lower increment
        final int balance = portfolio.getCzkAvailable();
        if (minimumRecommendation > balance) {
            return 0;
        } else if (minimumRecommendation > loan.getRemainingInvestment()) {
            return 0;
        }
        final int maxAllowedInvestmentIncrement = InvestmentStrategy.MINIMAL_INVESTMENT_INCREMENT;
        final int recommendedAmount = Math.min(balance, maximumRecommendation); // to never go over balance
        final int result = (recommendedAmount / maxAllowedInvestmentIncrement) * maxAllowedInvestmentIncrement;
        if (result < minimumRecommendation) {
            return 0;
        } else {
            return result;
        }
    }

}
