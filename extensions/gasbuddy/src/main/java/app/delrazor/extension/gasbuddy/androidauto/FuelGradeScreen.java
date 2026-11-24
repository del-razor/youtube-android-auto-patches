package app.delrazor.extension.gasbuddy.androidauto;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;

/** Picks the fuel grade. Returns the chosen grade as the screen result. */
final class FuelGradeScreen extends Screen {

    FuelGradeScreen(@NonNull CarContext carContext) {
        super(carContext);
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        final FuelGrade[] grades = FuelGrade.values();
        ItemList.Builder list = new ItemList.Builder()
                .setSelectedIndex(CarPrefs.grade(getCarContext()).ordinal())
                .setOnSelectedListener(index -> {
                    FuelGrade grade = grades[index];
                    CarPrefs.setGrade(getCarContext(), grade);
                    setResult(grade);
                    finish();
                });
        for (FuelGrade grade : grades) {
            list.addItem(new Row.Builder().setTitle(grade.label).build());
        }
        return new ListTemplate.Builder()
                .setTitle("Fuel")
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build())
                .build();
    }
}
