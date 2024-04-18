function getWatts(amps, voltage)
{
    return amps * voltage;
}

function getKWatts(amps, voltage)
{
    return ((amps * voltage) * 0.001);
}


