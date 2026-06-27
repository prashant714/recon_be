package com.reconciliation.oms.connector;

import com.reconciliation.connection.entity.ProviderConnection;
import java.time.OffsetDateTime;
import java.util.List;

public interface OmsConnector {

    String getProvider();

    List<OmsOrder> fetchOrders(ProviderConnection connection,
                               OffsetDateTime from, OffsetDateTime to);

    void testConnection(ProviderConnection connection);
}
