package org.bytesoft.bytetcc;

public class TransactionContext extends org.bytesoft.transaction.TransactionContext {
	private static final long serialVersionUID = 1L;

	private boolean compensable;

	public TransactionContext clone() {
		TransactionContext that = new TransactionContext();
		that.setXid(this.getXid());
		that.setCreatedTime(this.getCreatedTime());
		that.setExpiredTime(this.getExpiredTime());
		that.setCompensable(this.isCompensable());
		return that;
	}

	public boolean isCompensable() {
		return compensable;
	}

	public void setCompensable(boolean compensable) {
		this.compensable = compensable;
	}

}
