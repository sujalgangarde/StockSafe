package com.stocksafe.alerts.forecast;

import com.stocksafe.alerts.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pro feature. Average daily sales of the last 30 days, days of stock left,
 * and how many to order to cover the next 14 days.
 */
@Service
public class ForecastService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ForecastService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public void computeFor(List<UUID> tenants) {
        for (UUID t : tenants) {
            TenantContext.callAs(t, () -> tx.execute(s -> {
                jdbc.update("""
                        insert into forecasts(tenant_id, shop_id, product_id, product_name, avg_daily, stock,
                                              days_of_cover, suggested_qty, computed_at)
                        select current_tenant(), p.shop_id, p.product_id, p.name, s.avg_daily, st.stock,
                               case when s.avg_daily > 0 then round(st.stock / s.avg_daily, 1) end,
                               greatest(0, ceil(s.avg_daily * 14 - st.stock))::int, now()
                        from product_snapshots p
                        join (select product_id, sum(qty)::numeric / 30 as avg_daily from sales_daily
                              where day >= current_date - 30 group by product_id) s on s.product_id = p.product_id
                        join (select product_id, coalesce(sum(qty) filter (where expiry_date is null
                                 or expiry_date >= current_date), 0)::int as stock
                              from batch_snapshots group by product_id) st on st.product_id = p.product_id
                        on conflict (tenant_id, product_id) do update set avg_daily = excluded.avg_daily,
                          stock = excluded.stock, days_of_cover = excluded.days_of_cover,
                          suggested_qty = excluded.suggested_qty, product_name = excluded.product_name,
                          computed_at = now()""");
                return null;
            }));
        }
    }
}
