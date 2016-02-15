package org.bytesoft.bytetcc.aware;

import org.bytesoft.compensable.CompensableBeanFactory;

public interface CompensableBeanFactoryAware {
	public static final String BEAN_FACTORY_FIELD_NAME = "beanFactory";

	public void setBeanFactory(CompensableBeanFactory tbf);

}
