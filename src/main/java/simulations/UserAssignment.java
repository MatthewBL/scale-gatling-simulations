package simulations;

public final class UserAssignment {
  private final int index;
  private final String userType;
  private final String usageType;
  private final double hourlyRequests;
  private final int initialUnits;

  public UserAssignment(int index, String userType, String usageType, double hourlyRequests, int initialUnits) {
    this.index = index;
    this.userType = userType;
    this.usageType = usageType;
    this.hourlyRequests = hourlyRequests;
    this.initialUnits = initialUnits;
  }

  public int getIndex() {
    return index;
  }

  public String getUserType() {
    return userType;
  }

  public String getUsageType() {
    return usageType;
  }

  public double getHourlyRequests() {
    return hourlyRequests;
  }

  public int getInitialUnits() {
    return initialUnits;
  }
}
